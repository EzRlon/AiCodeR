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
    executable_path: Optional[str] = None  # resolved at launch; None = auto-detect
    # 远端桌面按桌面浏览器比例开（1280x800，宽高比 1.6）：全屏锁横屏、浮窗默认也取
    # 1.6 比例，scaleViewport 铺满时黑边最小。Xvfb 的尺寸启动即写死、运行中无法改
    # （--addmode 会 BadMatch，--fb 被拒），所以必须在这里定好。
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

    def _resolve_chromium(self) -> Optional[str]:
        # 优先级：env 强制指定 → playwright 自带缓存（chrome-linux/chrome，完整二进制）
        # → 系统 chromium。系统包装器（Debian 的 /usr/bin/chromium 是 shell 套壳，
        # PRoot 内启动即 SIGTRAP）不可靠，放最后并做可执行文件大小校验兜底。
        import os
        import shutil
        import glob
        env_path = os.environ.get("MULTI_LLM_CHROMIUM")
        if env_path and Path(env_path).exists():
            return env_path
        cache = Path.home() / ".cache" / "ms-playwright"
        for cand in sorted(glob.glob(str(cache / "chromium-*" / "chrome-linux" / "chrome")), reverse=True):
            if Path(cand).exists() and os.access(cand, os.X_OK):
                return cand
        for cand in ("/usr/bin/chromium", "/usr/bin/chromium-browser"):
            # 套壳脚本体积极小（真二进制几十 MB），小于 1MB 视为包装器，仅在无更好选择时用
            if Path(cand).exists() and Path(cand).stat().st_size > 1_000_000:
                return cand
        for name in ("chromium", "chromium-browser"):
            found = shutil.which(name)
            if found and "/snap/" not in found and Path(found).stat().st_size > 1_000_000:
                return found
        return None

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
                    chromium_path = self.executable_path or self._resolve_chromium()
                    launch_kw: dict = {
                        "user_data_dir": str(self.user_data_dir),
                        "headless": self.headless,
                        "args": [
                            "--disable-blink-features=AutomationControlled",
                            "--no-first-run",
                            "--no-default-browser-check",
                            "--disable-gpu",
                            # PRoot 容器以 root 运行且无 /dev/shm（mkdir 被拒）：
                            # 缺这两个参数 Chromium 启动即 SIGTRAP/FATAL，正常 Linux 无副作用
                            "--no-sandbox",
                            "--disable-dev-shm-usage",
                            # 窗口必须跟 Xvfb 桌面同尺寸：Playwright 的 viewport 只定页面视口，
                            # 不定窗口，不同步时窗口小于桌面，VNC 里会看到下方大片黑。
                            f"--window-size={self.viewport_width},{self.viewport_height}",
                            "--window-position=0,0",
                            "--start-maximized",
                        ],
                        "viewport": {"width": self.viewport_width, "height": self.viewport_height},
                    }
                    if chromium_path:
                        launch_kw["executable_path"] = chromium_path
                    # 无系统浏览器时省略 executable_path，走 playwright 自带 chromium
                    self._context = await self._playwright.chromium.launch_persistent_context(**launch_kw)
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
                 "-localhost", "-forever", "-shared", "-nopw", "-quiet", "-noshm", "-xrandr"],
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