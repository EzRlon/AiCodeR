"""Base client with shared logic; concrete clients only override hooks."""
from __future__ import annotations

import asyncio
import time
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Optional

from playwright.async_api import Page, TimeoutError as PWTimeoutError

from models import AskRequest, AskResponse, MODEL_REGISTRY, ModelConfig, SelectorConfig
from browser_manager import browser_manager
from sel_configs.configs import get_selectors
from utils import (
    take_debug_screenshot,
    save_base64_images,
    cleanup_temp_files,
    AuthError,
    RateLimitError,
    SelectorError,
    TimeoutError,
    BrowserDisconnectedError,
    default_retry,
    rate_limit_retry,
)


class BaseClient(ABC):
    """Shared automation logic for all model sites."""

    def __init__(self, model_name: str):
        self.model_name = model_name
        self.config: ModelConfig = MODEL_REGISTRY[model_name]
        self.selectors: SelectorConfig = get_selectors(model_name)
        self._page: Optional[Page] = None
        self._initialized = False

    # ========================================================
    # Public API
    # ========================================================

    async def ask(self, req: AskRequest) -> AskResponse:
        """Main entry: ensure page, upload files, send prompt, wait response."""
        start = time.perf_counter()
        await self._ensure_page()

        # Upload files/images first if any
        temp_files: list[Path] = []
        try:
            if req.file_paths or req.images_base64:
                await self._upload_files(req.file_paths, req.images_base64, temp_files)

            # Send query and wait for response
            content = await self._send_and_wait(req.query, req.timeout)

            latency_ms = int((time.perf_counter() - start) * 1000)
            return AskResponse(
                content=content,
                model=self.model_name,
                usage=None,  # Sites don't expose usage
                latency_ms=latency_ms,
            )
        finally:
            cleanup_temp_files(temp_files)

    # ========================================================
    # Internal workflow (overridable hooks)
    # ========================================================

    async def _ensure_page(self) -> None:
        """Create page, navigate, verify login."""
        if self._initialized and self._page and not self._page.is_closed():
            return

        self._page = await browser_manager.new_page()
        await self._page.goto(self.config.url, wait_until="domcontentloaded", timeout=30000)

        # Wait for input box as login indicator
        try:
            await self._page.wait_for_selector(self.selectors.input_box, timeout=15000)
        except PWTimeoutError:
            await take_debug_screenshot(self._page, f"{self.model_name}_no_input")
            raise AuthError(f"{self.model_name}: not logged in (input box not found)")

        self._initialized = True

    @default_retry
    async def _send_and_wait(self, query: str, timeout: int) -> str:
        """Type query, click send, wait for completion."""
        page = self._page
        assert page

        # Click new chat if needed (some sites require explicit new chat)
        await self._maybe_click_new_chat()

        # Fill input
        await self._fill_input(query)

        # Click send
        await self._click_send()

        # Wait for response to complete
        return await self._wait_response_complete(timeout)

    async def _maybe_click_new_chat(self) -> None:
        """Hook: click new chat button if site requires it."""
        # Default: do nothing; override in subclasses if needed
        pass

    async def _fill_input(self, query: str) -> None:
        """Type query into input box (handles both textarea and contenteditable)."""
        page = self._page
        assert page

        # Try each selector in order (comma-separated)
        for sel in self.selectors.input_box.split(","):
            sel = sel.strip()
            loc = page.locator(sel).first
            if await loc.count() > 0:
                await loc.fill(query)
                return
        raise SelectorError(f"{self.model_name}: input box not found with selectors: {self.selectors.input_box}")

    async def _click_send(self) -> None:
        """Click send button."""
        page = self._page
        assert page

        for sel in self.selectors.send_btn.split(","):
            sel = sel.strip()
            loc = page.locator(sel).first
            if await loc.count() > 0:
                await loc.click()
                return
        # Fallback: Enter sends on most modern chat UIs
        await page.keyboard.press("Enter")

    async def _wait_response_complete(self, timeout: int) -> str:
        """Wait until response finishes (regenerate button appears or streaming stops)."""
        page = self._page
        assert page

        # Wait for response container to appear
        container_sel = self.selectors.response_container
        await page.wait_for_selector(container_sel, timeout=10000)

        # Poll for completion flag
        deadline = time.time() + timeout
        last_text = ""
        stable = 0
        while time.time() < deadline:
            # Check for rate limit / captcha
            await self._check_error_indicators()

            # Check completion flag
            complete_sel = self.selectors.response_complete_flag
            if complete_sel:
                for sel in complete_sel.split(","):
                    sel = sel.strip()
                    if await page.locator(sel).count() > 0:
                        # Response complete — extract full text
                        return await self._extract_response_text()

            # For streaming: get current text
            current = await self._extract_response_text()
            if current and current == last_text:
                stable += 1
                if stable >= 12:  # ~6s without changes → generation finished
                    return current
            else:
                stable = 0
            last_text = current

            await asyncio.sleep(0.5)

        # Timeout — return whatever we have
        if last_text:
            return last_text
        raise TimeoutError(f"{self.model_name}: response timeout after {timeout}s")

    async def _extract_response_text(self) -> str:
        """Extract full response text from the last assistant message."""
        page = self._page
        assert page

        # Get all response containers, take the last one
        containers = page.locator(self.selectors.response_container)
        count = await containers.count()
        if count == 0:
            return ""
        last = containers.nth(count - 1)
        return (await last.inner_text()).strip()

    async def _check_error_indicators(self) -> None:
        """Check for captcha or rate limit, raise appropriate exception."""
        page = self._page
        assert page

        if self.selectors.captcha_indicator:
            for sel in self.selectors.captcha_indicator.split(","):
                if await page.locator(sel.strip()).count() > 0:
                    await take_debug_screenshot(page, f"{self.model_name}_captcha")
                    raise AuthError(f"{self.model_name}: CAPTCHA detected")

        if self.selectors.rate_limit_indicator:
            for sel in self.selectors.rate_limit_indicator.split(","):
                if await page.locator(sel.strip()).count() > 0:
                    await take_debug_screenshot(page, f"{self.model_name}_ratelimit")
                    raise RateLimitError(f"{self.model_name}: rate limited")

    async def _upload_files(self, file_paths: list[str], images_base64: list[str], temp_files: list[Path]) -> None:
        """Upload files and images via file input."""
        page = self._page
        assert page

        all_paths = list(file_paths)
        if images_base64:
            temp_files.extend(save_base64_images(images_base64))
            all_paths.extend(str(p) for p in temp_files[-len(images_base64):])

        if not all_paths:
            return

        # Click attach button if defined
        if self.selectors.attach_btn:
            for sel in self.selectors.attach_btn.split(","):
                sel = sel.strip()
                loc = page.locator(sel).first
                if await loc.count() > 0:
                    await loc.click()
                    await page.wait_for_timeout(500)
                    break

        # Set files on file input
        file_input = page.locator(self.selectors.file_input).first
        await file_input.set_input_files(all_paths)

        # Wait for upload to complete (site-specific, default 10s)
        await page.wait_for_timeout(10000)

    async def close(self) -> None:
        """Cleanup page."""
        if self._page and not self._page.is_closed():
            await self._page.close()
        self._initialized = False


# ========================================================
# Client registry (lazy singletons)
# ========================================================

_clients: dict[str, BaseClient] = {}


def get_client(model: str) -> BaseClient:
    """Get or create client singleton for model."""
    if model not in _clients:
        import clients as _clients_pkg
        cls_map = {
            c.__name__.lower().replace("client", ""): c
            for c in (getattr(_clients_pkg, n) for n in _clients_pkg.__all__)
            if isinstance(c, type)
        }
        if model not in cls_map:
            raise ValueError(f"No client for model: {model}")
        _clients[model] = cls_map[model](model)
    return _clients[model]


async def close_all_clients() -> None:
    for c in _clients.values():
        await c.close()
    _clients.clear()