"""Shared utilities: retry policies, debug screenshots, exception hierarchy."""
from __future__ import annotations

import asyncio
import base64
import time
from pathlib import Path
from typing import TypeVar, Callable, Awaitable

from tenacity import (
    retry,
    stop_after_attempt,
    wait_exponential_jitter,
    retry_if_exception_type,
    before_sleep_log,
)
import logging

logger = logging.getLogger(__name__)

# ============================================================
# Exception hierarchy
# ============================================================

class MCPError(Exception):
    """Base exception for all MCP-related errors."""
    pass


class AuthError(MCPError):
    """Raised when login is required or session expired."""
    pass


class RateLimitError(MCPError):
    """Raised when site returns rate limit / quota exceeded."""
    pass


class SelectorError(MCPError):
    """Raised when a selector cannot be found or is ambiguous."""
    pass


class TimeoutError(MCPError):
    """Raised when an operation exceeds its timeout."""
    pass


class BrowserDisconnectedError(MCPError):
    """Raised when CDP connection is lost."""
    pass


# ============================================================
# Retry policies
# ============================================================

# Default: 3 attempts, exponential backoff 1s-4s with jitter
# TimeoutError is NOT retried: the message was already delivered — retrying would post duplicates
# (e.g. thinking models silently generating past the caller's timeout)
default_retry = retry(
    reraise=True,
    stop=stop_after_attempt(3),
    wait=wait_exponential_jitter(initial=1, max=4),
    retry=retry_if_exception_type((BrowserDisconnectedError, SelectorError)),
    before_sleep=before_sleep_log(logger, logging.WARNING),
)

# Rate limit: longer backoff, more attempts
rate_limit_retry = retry(
    reraise=True,
    stop=stop_after_attempt(5),
    wait=wait_exponential_jitter(initial=10, max=60),
    retry=retry_if_exception_type(RateLimitError),
    before_sleep=before_sleep_log(logger, logging.WARNING),
)

# Auth: no retry — caller must handle login
auth_retry = retry(
    reraise=True,
    stop=stop_after_attempt(1),
    retry=retry_if_exception_type(AuthError),
)


# ============================================================
# Debug screenshot helper
# ============================================================

DEBUG_DIR = Path.home() / "mcp-debug"
DEBUG_DIR.mkdir(exist_ok=True)


async def take_debug_screenshot(page, tag: str) -> Path:
    """Save a full-page screenshot for debugging."""
    ts = int(time.time() * 1000)
    path = DEBUG_DIR / f"{tag}_{ts}.png"
    await page.screenshot(path=str(path), full_page=True)
    logger.debug("Debug screenshot saved: %s", path)
    return path


# ============================================================
# Base64 image handling
# ============================================================

TEMP_UPLOAD_DIR = Path.home() / "mcp-uploads"
TEMP_UPLOAD_DIR.mkdir(exist_ok=True)


def save_base64_images(images_base64: list[str]) -> list[Path]:
    """Decode base64 images to temp files, return paths."""
    paths = []
    for i, b64 in enumerate(images_base64):
        # Strip data URL prefix if present
        if b64.startswith("data:"):
            b64 = b64.split(",", 1)[1]
        data = base64.b64decode(b64)
        ext = ".png"  # default
        # Try to detect from magic bytes
        if data.startswith(b"\xff\xd8\xff"):
            ext = ".jpg"
        elif data.startswith(b"\x89PNG\r\n\x1a\n"):
            ext = ".png"
        elif data.startswith(b"GIF8"):
            ext = ".gif"
        elif data.startswith(b"RIFF") and b"WEBP" in data[:12]:
            ext = ".webp"
        path = TEMP_UPLOAD_DIR / f"upload_{int(time.time()*1000)}_{i}{ext}"
        path.write_bytes(data)
        paths.append(path)
    return paths


def cleanup_temp_files(paths: list[Path]) -> None:
    """Best-effort cleanup of temp upload files."""
    for p in paths:
        try:
            p.unlink(missing_ok=True)
        except Exception:
            pass


# ============================================================
# Generic async retry helper
# ============================================================

T = TypeVar("T")


async def async_retry(
    func: Callable[..., Awaitable[T]],
    *args,
    retries: int = 3,
    base_delay: float = 1.0,
    max_delay: float = 30.0,
    exceptions: tuple[type[Exception], ...] = (Exception,),
    **kwargs,
) -> T:
    """Simple async retry with exponential backoff (no tenacity dependency)."""
    last_exc = None
    for attempt in range(retries):
        try:
            return await func(*args, **kwargs)
        except exceptions as e:
            last_exc = e
            if attempt == retries - 1:
                break
            delay = min(base_delay * (2**attempt), max_delay)
            logger.warning("Attempt %d/%d failed: %s. Retrying in %.1fs", attempt + 1, retries, e, delay)
            await asyncio.sleep(delay)
    raise last_exc