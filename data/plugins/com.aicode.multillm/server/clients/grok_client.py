"""Grok client — standard textarea."""
from clients.base import BaseClient


class GrokClient(BaseClient):
    """Grok-2 at grok.com"""

    async def _maybe_click_new_chat(self) -> None:
        """Grok has 'New Conversation' button."""
        page = self._page
        if not page:
            return
        for sel in self.selectors.new_chat_btn.split(","):
            sel = sel.strip()
            loc = page.locator(sel).first
            if await loc.count() > 0:
                await loc.click()
                await page.wait_for_timeout(500)
                break