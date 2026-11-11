"""Selector configurations for each supported model site.

This is the ONLY file that needs updates when a site changes its UI.
All client logic reads from here — zero code changes for selector updates.
"""
from __future__ import annotations

from models import SelectorConfig

# ============================================================
# SELECTOR CONFIGS — one per model
# ============================================================

SELECTORS: dict[str, SelectorConfig] = {
    "deepseek": SelectorConfig(
        new_chat_btn='button:has-text("新建对话"), button:has-text("New Chat")',
        input_box='#chat-input, textarea[placeholder*="消息"], textarea[placeholder*="Message"], textarea[data-testid="chat-input"]',
        send_btn='button[data-testid="send-button"], button:has-text("发送"), div[role="button"].ds-button--circle',
        response_container='.ds-assistant-message-main-content',
        response_complete_flag='button:has-text("重新生成"), button:has-text("Regenerate")',
        file_input='input[type="file"]',
        attach_btn='button:has-text("附件"), button[data-testid="attach-button"]',
        captcha_indicator='.captcha, [id*="captcha"]',
        rate_limit_indicator='.rate-limit, text="太快了"',
    ),
    "chatgpt": SelectorConfig(
        new_chat_btn='button:has-text("New chat"), a[href="/"]',
        input_box='#prompt-textarea, textarea[data-id="root"]',
        send_btn='button[data-testid="send-button"], button:has-text("Send")',
        response_container='.markdown, [data-message-author-role="assistant"]',
        response_complete_flag='button:has-text("Regenerate"), button[aria-label="Regenerate response"]',
        file_input='input[type="file"]',
        attach_btn='button[data-testid="attach-button"], button:has-text("Attach")',
        captcha_indicator='.cf-challenge, [id*="challenge"]',
        rate_limit_indicator='text="Rate limit", text="Too many requests"',
    ),
    "claude": SelectorConfig(
        new_chat_btn='button:has-text("New Chat"), a[href="/new"]',
        input_box='div[contenteditable="true"][data-testid="chat-input"], textarea[placeholder*="Talk to"]',
        send_btn='button[data-testid="send-button"], button:has-text("Send")',
        response_container='.font-claude, [data-testid="assistant-message"]',
        response_complete_flag='button:has-text("Retry"), button[aria-label="Retry"]',
        file_input='input[type="file"]',
        attach_btn='button[data-testid="attachment-button"], button:has-text("Attach")',
        captcha_indicator='.captcha, [id*="captcha"]',
        rate_limit_indicator='text="rate limit", text="too many requests"',
    ),
    "gemini": SelectorConfig(
        new_chat_btn='button:has-text("New chat"), a[href*="new"]',
        input_box='rich-textarea[placeholder*="Enter"], textarea[aria-label*="Enter"]',
        send_btn='button[aria-label*="Send"], button:has-text("Send")',
        response_container='.model-response, [data-response-index]',
        response_complete_flag='button:has-text("Regenerate"), button[aria-label*="Regenerate"]',
        file_input='input[type="file"]',
        attach_btn='button[aria-label*="Upload"], button:has-text("Add files")',
        captcha_indicator='.captcha, [id*="captcha"]',
        rate_limit_indicator='text="rate limit", text="quota exceeded"',
    ),
    "grok": SelectorConfig(
        new_chat_btn='button:has-text("New Conversation"), a[href="/"]',
        input_box='textarea[placeholder*="Ask"], div[contenteditable="true"][data-testid="input"]',
        send_btn='button[data-testid="send-button"], button:has-text("Send")',
        response_container='.message-content, [data-testid="assistant-message"]',
        response_complete_flag='button:has-text("Regenerate"), button[aria-label="Regenerate"]',
        file_input='input[type="file"]',
        attach_btn='button:has-text("Attach"), button[data-testid="attach"]',
        captcha_indicator='.captcha, [id*="captcha"]',
        rate_limit_indicator='text="rate limit", text="too many requests"',
    ),
    "qwen": SelectorConfig(
        new_chat_btn='button:has-text("新建对话"), button:has-text("New Chat")',
        input_box='textarea[placeholder*="输入"], textarea[placeholder*="Ask"], textarea[placeholder*="Enter"], #chat-input',
        send_btn='[class*="right-button-send"], button:has-text("发送"), button:has-text("Send"), button[data-testid="send"]',
        response_container='.qwen-chat-message-assistant',
        response_complete_flag='button:has-text("重新生成"), button:has-text("Regenerate")',
        file_input='input[type="file"]',
        attach_btn='button:has-text("上传"), button:has-text("Upload"), button[data-testid="attach"]',
        captcha_indicator='.captcha, [id*="captcha"]',
        rate_limit_indicator='text="频率限制", text="rate limit"',
    ),
}


def get_selectors(model: str) -> SelectorConfig:
    """Get selector config for a model, raise if not found."""
    try:
        return SELECTORS[model]
    except KeyError:
        raise ValueError(f"No selector config for model: {model}. Available: {list(SELECTORS.keys())}")