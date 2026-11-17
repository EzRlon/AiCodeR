"""MCP Server entry point: registers tools for 6 models + ask_all."""
from __future__ import annotations

import asyncio
import json
import logging
from contextlib import asynccontextmanager
from typing import Any

from mcp.server import Server
from mcp.server.context import ServerRequestContext
from mcp.server.stdio import stdio_server
from mcp.types import (
    Tool,
    TextContent,
    CallToolResult,
    ListToolsResult,
    ListToolsRequest,
    CallToolRequestParams,
)

from models import AskRequest, AskResponse, MODEL_REGISTRY, ALL_MODEL_NAMES
from clients import get_client, close_all_clients

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

server = Server("mcp-multi-llm")

# One browser, one shared page per model client: serialize all asks so
# concurrent requests cannot interleave on the same page.
_ask_lock = asyncio.Lock()


# ============================================================
# Tool definitions
# ============================================================

SINGLE_MODEL_SCHEMA = {
    "type": "object",
    "properties": {
        "query": {"type": "string", "description": "User question or prompt"},
        "file_paths": {"type": "array", "items": {"type": "string"}, "default": []},
        "images_base64": {"type": "array", "items": {"type": "string"}, "default": []},
        "stream": {"type": "boolean", "default": False},
        "timeout": {"type": "integer", "default": 120, "minimum": 10, "maximum": 600},
    },
    "required": ["query"],
}

ASK_ALL_SCHEMA = {
    "type": "object",
    "properties": {
        "query": {"type": "string", "description": "User question or prompt"},
        "file_paths": {"type": "array", "items": {"type": "string"}, "default": []},
        "images_base64": {"type": "array", "items": {"type": "string"}, "default": []},
        "timeout": {"type": "integer", "default": 180, "minimum": 10, "maximum": 600},
    },
    "required": ["query"],
}


# ============================================================
# Build tool list
# ============================================================

TOOLS: list[Tool] = []

for model_name in ALL_MODEL_NAMES:
    TOOLS.append(
        Tool(
            name=f"ask_{model_name}",
            description=f"Ask {model_name} a question (supports file/image upload)",
            inputSchema=SINGLE_MODEL_SCHEMA,
        )
    )

TOOLS.append(
    Tool(
        name="ask_all",
        description="Ask all 6 models in parallel, return aggregated JSON",
        inputSchema=ASK_ALL_SCHEMA,
    )
)

TOOLS.append(
    Tool(
        name="open_browser",
        description=(
            "Open the multi-llm login browser (headed Chromium on the VNC display :1). "
            "Call with model=<name> to open that model's site for login, or model=home for a blank page. "
            "The browser renders into the VNC overlay, so the user can log in there."
        ),
        inputSchema={
            "type": "object",
            "properties": {
                "model": {
                    "type": "string",
                    "description": "Model name from MODEL_REGISTRY (e.g. deepseek), or 'home' for about:blank",
                    "default": "home",
                },
                "url": {
                    "type": "string",
                    "description": "Optional explicit URL to open (overrides model)",
                },
            },
        },
    )
)


# ============================================================
# Request handlers
# ============================================================

async def _handle_list_tools(
    ctx: ServerRequestContext, request: ListToolsRequest
) -> ListToolsResult:
    return ListToolsResult(tools=TOOLS)


async def _handle_call_tool(
    ctx: ServerRequestContext, params: CallToolRequestParams
) -> CallToolResult:
    name = params.name
    arguments = params.arguments or {}

    if name == "open_browser":
        # 拉起 headed 浏览器画到 VNC 显示器（用户在浮窗里可见）。
        # model=具体模型 → 开该模型站点（便于登录）；model=home/url=... → 开指定页。
        try:
            from browser_manager import browser_manager

            model = (arguments.get("model") or "home").strip()
            url = arguments.get("url")
            if not url and model != "home":
                cfg = MODEL_REGISTRY.get(model)
                if cfg is None:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"Unknown model: {model}")],
                        isError=True,
                    )
                url = cfg.url
            page = await browser_manager.new_page()
            if url:
                await page.goto(url, wait_until="domcontentloaded", timeout=60_000)
            else:
                await page.set_content(
                    "<body style='background:#f5f7fa;color:#111827;font-family:sans-serif;"
                    "display:flex;align-items:center;justify-content:center;height:100vh;"
                    "margin:0;text-align:center'>"
                    "<div><h1 style='font-size:44px;margin:0'>multi-llm browser ready</h1>"
                    "<p style='font-size:24px;color:#374151;margin-top:20px'>"
                    "This is the advisor browser. Type a model URL above to sign in.</p>"
                    "</div></body>"
                )
            return CallToolResult(
                content=[TextContent(type="text", text=f"OK: browser open at {url or 'home'}")]
            )
        except Exception as e:
            logger.error("open_browser failed: %s", e)
            return CallToolResult(
                content=[TextContent(type="text", text=f"open_browser failed: {e}")],
                isError=True,
            )

    if name == "ask_all":
        req = AskRequest(**arguments)
        results: dict[str, AskResponse] = {}

        async def ask_one(model: str) -> None:
            try:
                client = get_client(model)
                async with _ask_lock:
                    results[model] = await client.ask(req)
            except Exception as e:
                logger.error("ask_all: %s failed: %s", model, e)
                results[model] = AskResponse(
                    content=f"ERROR: {e}",
                    model=model,
                    usage=None,
                    latency_ms=0,
                )

        await asyncio.gather(*[ask_one(m) for m in ALL_MODEL_NAMES])
        return CallToolResult(
            content=[TextContent(type="text", text=json.dumps({k: v.model_dump() for k, v in results.items()}))]
        )

    if name.startswith("ask_"):
        model = name[4:]
        if model not in MODEL_REGISTRY:
            return CallToolResult(
                content=[TextContent(type="text", text=f"Unknown model: {model}")],
                isError=True,
            )
        arguments.pop("model", None)
        req = AskRequest(**arguments, model=model)
        client = get_client(model)
        try:
            async with _ask_lock:
                resp = await client.ask(req)
        except Exception as e:
            logger.error("ask_%s failed: %s", model, e)
            resp = AskResponse(content=f"ERROR: {e}", model=model, usage=None, latency_ms=0)
        return CallToolResult(
            content=[TextContent(type="text", text=resp.model_dump_json())]
        )

    return CallToolResult(
        content=[TextContent(type="text", text=f"Unknown tool: {name}")],
        isError=True,
    )


server.add_request_handler("tools/list", ListToolsRequest, _handle_list_tools)
server.add_request_handler("tools/call", CallToolRequestParams, _handle_call_tool)


# ============================================================
# Lifecycle
# ============================================================

@asynccontextmanager
async def lifespan(app: Server):
    logger.info("mcp-multi-llm starting up...")
    try:
        yield
    finally:
        logger.info("Shutting down clients...")
        await close_all_clients()
        logger.info("mcp-multi-llm stopped.")


server.lifespan = lifespan


# ============================================================
# CLI entry
# ============================================================

async def main() -> None:
    """Run MCP server over stdio."""
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())