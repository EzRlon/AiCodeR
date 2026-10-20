"""Browser lifecycle management: launch persistent Chromium, reuse profile."""
from __future__ import annotations

import asyncio
import logging
import os
import socket
import subprocess
import time
from pathlib import Path
from typing import Optional, TYPE_CHECKING

if TYPE_CHECKING:
    from playwright.async_api import Browser, BrowserContext, Playwright
else:
    Browser = object
    BrowserContext = object
    Playwright = object

logger = logging.getLogger(__name__)


class BrowserManager:
    """Manages a single persistent Chromium instance launched by Playwright."""

    _playwright: Optional[Playwright] = None
    _browser: Optional[Browser] = None
    _context: Optional[BrowserContext] = None

    # Configurable via env or constructor
    user_data_dir: Path = Path.home() / "chrome_mcp_multi_llm"
    headless: bool = False          # Keep headed so user can see/login/handle captcha
    executable_path: str = "/usr/bin/chromium"  # system chromium installed via apk
    viewport_width: int = 1280
    viewport_height: int = 800
    x_display: str = ":1"
    vnc_port: int = 5901

    # Internal
    _heartbeat_task: Optional[asyncio.Task] = None
    _heartbeat_interval: int = 30  # seconds
    _lock: Optional[asyncio.Lock] = None

    def _get_lock(self) -> asyncio.Lock:
        # Serialized cold start: concurrent ask_all calls must not race
        # launch_persistent_context on the same profile directory.
        if self._lock is None:
            self._lock = asyncio.Lock()
        return self._lock

    async def start(self) -> Browser:
        """Launch or reuse persistent Chromium, return ready Browser."""
        if self._browser and self._browser.is_connected():
            return self._browser

        async with self._get_lock():
            if self._browser and self._browser.is_connected():
                return self._browser

            await self._cleanup()
            # Sync setup (pkill, Xvfb/vnc spawn + readiness waits) would
            # otherwise block the event loop for up to ~20s.
            await asyncio.to_thread(self._prepare_environment)
            # Cold launches on this device intermittently die during the
            # handshake and leave orphans holding the profile; retry cleans
            # them via _kill_stale_chromium and succeeds by attempt 2-3.
            last_err: Optional[Exception] = None
            for attempt in range(3):
                try:
                    pw_cm = __import__("playwright.async_api", fromlist=["async_playwright"]).async_playwright()
                    self._playwright = await pw_cm.start()
                    self._context = await self._playwright.chromium.launch_persistent_context(
                        user_data_dir=str(self.user_data_dir),
                        headless=self.headless,
                        executable_path=self.executable_path,
                        args=[
                            "--disable-blink-features=AutomationControlled",
                            "--no-first-run",
                            "--no-default-browser-check",
                            "--disable-gpu",
                        ],
                        viewport={"width": self.viewport_width, "height": self.viewport_height},
                    )
                    self._browser = self._context.browser
                    last_err = None
                    break
                except Exception as e:  # noqa: BLE001 - retry any launch failure
                    last_err = e
                    logger.warning("browser launch attempt %d failed: %s", attempt + 1, str(e).splitlines()[0])
                    await self._cleanup()
                    await asyncio.to_thread(self._kill_stale_chromium)
                    await asyncio.sleep(2)
            if last_err is not None:
                raise last_err
            self._heartbeat_task = asyncio.create_task(self._heartbeat())
        return self._browser

    # ============================================================
    # Headless-container helpers: virtual display + VNC
    # ============================================================

    def _prepare_environment(self) -> None:
        """Kill stale chromium, then ensure virtual display + VNC (blocking; run in a thread)."""
        self._kill_stale_chromium()
        self._ensure_display_and_vnc()

    def _kill_stale_chromium(self) -> None:
        """After an abrupt server exit an orphan chromium may hold the profile lock.
        SIGTERM first, then SIGKILL survivors — launching while any of them still
        holds the profile makes the new chromium exit immediately."""
        pattern = f"user-data-dir={self.user_data_dir}"
        subprocess.run(
            ["pkill", "-f", pattern],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        for _ in range(12):
            if not self._stale_running(pattern):
                return
            time.sleep(0.25)
        subprocess.run(
            ["pkill", "-9", "-f", pattern],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        for _ in range(12):
            if not self._stale_running(pattern):
                return
            time.sleep(0.25)
        logger.warning("stale chromium still running after 6s")

    def _stale_running(self, pattern: str) -> bool:
        r = subprocess.run(
            ["pgrep", "-f", pattern],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return r.returncode == 0

    def _ensure_display_and_vnc(self) -> None:
        """No X display in the container: start Xvfb and expose it via localhost-only VNC."""
        if not os.environ.get("DISPLAY"):
            if not self._x_ready():
                stale = Path(f"/tmp/.X11-unix/X{self.x_display.lstrip(':')}")
                stale.unlink(missing_ok=True)  # socket file left by a dead Xvfb
                subprocess.Popen(
                    ["Xvfb", self.x_display, "-screen", "0",
                     f"{self.viewport_width}x{self.viewport_height}x24", "-nolisten", "tcp", "-nolock"],
                    start_new_session=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                for _ in range(50):
                    if self._x_ready():
                        break
                    time.sleep(0.1)
            if not self._x_ready():
                raise RuntimeError(f"Xvfb failed to start on display {self.x_display}")
            os.environ["DISPLAY"] = self.x_display
        # Xvfb/x11vnc are intentionally left running after close(): they serve
        # the VNC session used for manual site logins.
        for _ in range(3):
            if self._vnc_listening():
                break
            subprocess.Popen(
                ["x11vnc", "-display", self.x_display, "-rfbport", str(self.vnc_port),
                 "-localhost", "-forever", "-shared", "-nopw", "-quiet", "-noshm"],
                start_new_session=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            for _ in range(20):
                if self._vnc_listening():
                    break
                time.sleep(0.25)
        if not self._vnc_listening():
            logger.warning("x11vnc failed to start after retries; VNC login unavailable")

    def _x_ready(self) -> bool:
        s = socket.socket(socket.AF_UNIX)
        try:
            s.settimeout(0.5)
            s.connect(f"/tmp/.X11-unix/X{self.x_display.lstrip(':')}")
            return True
        except OSError:
            return False
        finally:
            s.close()

    def _vnc_listening(self) -> bool:
        with socket.socket() as s:
            s.settimeout(0.2)
            return s.connect_ex(("127.0.0.1", self.vnc_port)) == 0

    async def _heartbeat(self) -> None:
        """Periodically check browser connection, mark dead if lost."""
        while True:
            await asyncio.sleep(self._heartbeat_interval)
            if not self._browser or not self._browser.is_connected():
                self._browser = None
                self._context = None
                break

    async def get_context(self) -> BrowserContext:
        """Return the persistent context (launch if needed)."""
        if not self._context or not self._browser or not self._browser.is_connected():
            await self.start()
        return self._context

    async def new_page(self):
        """Create a new page in the shared persistent context."""
        ctx = await self.get_context()
        return await ctx.new_page()

    async def close(self) -> None:
        """Full shutdown."""
        if self._heartbeat_task:
            self._heartbeat_task.cancel()
            try:
                await self._heartbeat_task
            except asyncio.CancelledError:
                pass
        await self._cleanup()

    async def _cleanup(self) -> None:
        if self._context:
            await self._context.close()
            self._context = None
        if self._browser:
            await self._browser.close()
            self._browser = None
        if self._playwright:
            await self._playwright.stop()
            self._playwright = None


# Global singleton instance
browser_manager = BrowserManager()