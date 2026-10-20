"""Qwen client — Chinese/English UI."""
from clients.base import BaseClient


class QwenClient(BaseClient):
    """Qwen2.5/Max at chat.qwen.ai"""

    async def _maybe_click_new_chat(self) -> None:
        """Anonymous sessions pop a login modal after each reply that blocks
        the input area; reloading starts a clean conversation instead."""
        page = self._page
        if not page:
            return
        await page.reload(wait_until="domcontentloaded")
        await page.wait_for_selector(self.selectors.input_box, timeout=20000)
