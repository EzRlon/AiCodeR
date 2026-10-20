"""Data models and model registry for mcp-multi-llm."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal
from pydantic import BaseModel, Field


class AskRequest(BaseModel):
    """Unified request for all models."""
    query: str = Field(..., description="User question or prompt")
    file_paths: list[str] = Field(default_factory=list, description="Absolute local file paths to upload")
    images_base64: list[str] = Field(default_factory=list, description="Inline base64 images")
    model: Literal["deepseek", "chatgpt", "claude", "gemini", "grok", "qwen"] = Field(
        default="deepseek", description="Target model"
    )
    stream: bool = Field(default=False, description="Return streaming chunks if True")
    timeout: int = Field(default=120, ge=10, le=600, description="Per-request timeout seconds")


class AskResponse(BaseModel):
    """Unified response from all models."""
    content: str = Field(..., description="Model response text")
    model: str = Field(..., description="Model that generated the response")
    usage: dict | None = Field(default=None, description="Token usage if available")
    latency_ms: int = Field(..., description="End-to-end latency in milliseconds")


@dataclass(frozen=True)
class ModelConfig:
    """Static configuration per model."""
    name: str
    url: str
    client_cls: str  # Class name in clients/


@dataclass(frozen=True)
class SelectorConfig:
    """CSS/XPath selectors for a specific site."""
    # Navigation & input
    new_chat_btn: str
    input_box: str
    send_btn: str
    # Response detection
    response_container: str
    response_complete_flag: str
    # File/Image upload
    file_input: str
    attach_btn: str = ""
    # Anti-detection / error indicators
    captcha_indicator: str = ""
    rate_limit_indicator: str = ""


# ============================================================
# MODEL REGISTRY — single source of truth
# ============================================================
MODEL_REGISTRY: dict[str, ModelConfig] = {
    "deepseek": ModelConfig(
        name="deepseek",
        url="https://chat.deepseek.com",
        client_cls="DeepSeekClient",
    ),
    "chatgpt": ModelConfig(
        name="chatgpt",
        url="https://chatgpt.com",
        client_cls="ChatGPTClient",
    ),
    "claude": ModelConfig(
        name="claude",
        url="https://claude.ai",
        client_cls="ClaudeClient",
    ),
    "gemini": ModelConfig(
        name="gemini",
        url="https://gemini.google.com",
        client_cls="GeminiClient",
    ),
    "grok": ModelConfig(
        name="grok",
        url="https://grok.com",
        client_cls="GrokClient",
    ),
    "qwen": ModelConfig(
        name="qwen",
        url="https://chat.qwen.ai",
        client_cls="QwenClient",
    ),
}

ALL_MODEL_NAMES = tuple(MODEL_REGISTRY.keys())