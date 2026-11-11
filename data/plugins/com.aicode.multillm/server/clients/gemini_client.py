"""Gemini client — rich-textarea input."""
from clients.base import BaseClient


class GeminiClient(BaseClient):
    """Gemini 1.5 Flash/Pro at gemini.google.com"""

    async def _maybe_click_new_chat(self) -> None:
        """Gemini has 'New chat' button in sidebar."""
        page = self._page
        if not page:
            return
        for sel in self.selectors.new_chat_btn.split(","):
            sel = sel.strip()
            loc = page.locator(sel).first
            if await loc.count() > 0:
                await loc.click()
                await page.wait_for_timeout(800)
                break

    async def _fill_input(self, query: str) -> None:
        """Gemini uses rich-textarea (custom element)."""
        page = self._page
        assert page

        for sel in self.selectors.input_box.split(","):
            sel = sel.strip()
            loc = page.locator(sel).first
            if await loc.count() > 0:
                # rich-textarea has internal textarea
                inner = loc.locator("textarea").first
                if await inner.count() > 0:
                    await inner.fill(query)
                else:
                    await loc.evaluate(f"el => el.value = {query!r}")
                    await loc.press("Enter")
                return
        raise Exception(f"Gemini: input box not found with selectors: {self.selectors.input_box}")