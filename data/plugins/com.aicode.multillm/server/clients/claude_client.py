"""Claude client — requires explicit new chat."""
from clients.base import BaseClient


class ClaudeClient(BaseClient):
    """Claude 3.5 Sonnet at claude.ai"""

    async def _maybe_click_new_chat(self) -> None:
        """Claude always needs explicit 'New Chat'."""
        page = self._page
        if not page:
            return
        for sel in self.selectors.new_chat_btn.split(","):
            sel = sel.strip()
            loc = page.locator(sel).first
            if await loc.count() > 0:
                await loc.click()
                await page.wait_for_timeout(1000)
                break

    async def _fill_input(self, query: str) -> None:
        """Claude uses contenteditable div."""
        page = self._page
        assert page

        for sel in self.selectors.input_box.split(","):
            sel = sel.strip()
            loc = page.locator(sel).first
            if await loc.count() > 0:
                tag = await loc.evaluate("el => el.tagName.toLowerCase()")
                if tag == "textarea":
                    await loc.fill(query)
                else:
                    await loc.evaluate(f"el => el.innerText = {query!r}")
                    await loc.press("Enter")
                return
        raise Exception(f"Claude: input box not found with selectors: {self.selectors.input_box}")