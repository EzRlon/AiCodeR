"""ChatGPT client — handles contenteditable input."""
from clients.base import BaseClient


class ChatGPTClient(BaseClient):
    """GPT-4o mini at chatgpt.com"""

    async def _maybe_click_new_chat(self) -> None:
        """ChatGPT sidebar has 'New chat' link."""
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
        """ChatGPT uses contenteditable div, not textarea."""
        page = self._page
        assert page

        # Try contenteditable first
        for sel in self.selectors.input_box.split(","):
            sel = sel.strip()
            loc = page.locator(sel).first
            if await loc.count() > 0:
                # For contenteditable, use evaluate to set text
                tag = await loc.evaluate("el => el.tagName.toLowerCase()")
                if tag == "textarea":
                    await loc.fill(query)
                else:
                    await loc.evaluate(f"el => el.innerText = {query!r}")
                    await loc.press("Enter")  # Trigger change event
                return
        raise Exception(f"ChatGPT: input box not found with selectors: {self.selectors.input_box}")