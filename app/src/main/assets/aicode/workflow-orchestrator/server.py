#!/usr/bin/env python3
"""workflow-orchestrator MCP server: cross-session workflow state for team sessions.

Tools:
  - record_node    : record a lifecycle node transition for a session
  - get_status     : query node trail + gate state for a session
  - archive_review : store a review verdict (multi-llm or human) for a session
  - list_recent    : recent orchestration records across sessions

State: JSON file at $AICODE_HOME/workflow-orchestrator/state.json
(~/.aicode/workflow-orchestrator/state.json by default), written atomically.
Zero third-party dependencies: implements MCP stdio (JSON-RPC 2.0, line-delimited)
with the standard library only, so it runs with the bare python3 already in the container.
"""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "workflow-orchestrator", "version": "1.0.0"}

AICODE_HOME = Path(os.environ.get("AICODE_HOME", str(Path.home() / ".aicode")))
STATE_DIR = AICODE_HOME / "workflow-orchestrator"
STATE_FILE = STATE_DIR / "state.json"
MAX_NODE_TRAIL = 200
MAX_SESSIONS = 200


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {"sessions": {}, "reviews": []}


def save_state(state: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, STATE_FILE)


def prune(state: dict) -> None:
    sessions = state.get("sessions", {})
    if len(sessions) > MAX_SESSIONS:
        ranked = sorted(
            sessions.items(),
            key=lambda kv: kv[1].get("updatedAt", 0),
            reverse=True,
        )
        state["sessions"] = dict(ranked[:MAX_SESSIONS])
    reviews = state.get("reviews", [])
    if len(reviews) > MAX_NODE_TRAIL:
        state["reviews"] = reviews[-MAX_NODE_TRAIL:]


# ============================================================
# Tool implementations
# ============================================================

def tool_record_node(args: dict) -> str:
    session_id = str(args.get("session_id") or "").strip()
    node = str(args.get("node") or "").strip()
    if not session_id or not node:
        raise ValueError("session_id and node are required")
    state = load_state()
    sessions = state.setdefault("sessions", {})
    entry = sessions.setdefault(
        session_id,
        {"teamName": args.get("team_name") or "", "trail": [], "reviews": [], "createdAt": _now()},
    )
    if args.get("team_name"):
        entry["teamName"] = args["team_name"]
    entry["trail"] = (entry.get("trail") or [])[-(MAX_NODE_TRAIL - 1):]
    entry["trail"].append({"node": node, "detail": str(args.get("detail") or ""), "at": _now()})
    entry["updatedAt"] = _now()
    prune(state)
    save_state(state)
    return json.dumps({"ok": True, "sessionId": session_id, "nodeCount": len(entry["trail"])}, ensure_ascii=False)


def tool_get_status(args: dict) -> str:
    session_id = str(args.get("session_id") or "").strip()
    if not session_id:
        raise ValueError("session_id is required")
    state = load_state()
    entry = state.get("sessions", {}).get(session_id)
    if not entry:
        return json.dumps({"ok": True, "found": False, "sessionId": session_id}, ensure_ascii=False)
    reviews = state.get("reviews", [])
    session_reviews = [r for r in reviews if r.get("sessionId") == session_id]
    return json.dumps(
        {
            "ok": True,
            "found": True,
            "sessionId": session_id,
            "teamName": entry.get("teamName", ""),
            "trail": entry.get("trail", [])[-50:],
            "reviews": session_reviews[-20:],
            "updatedAt": entry.get("updatedAt", 0),
        },
        ensure_ascii=False,
    )


def tool_archive_review(args: dict) -> str:
    session_id = str(args.get("session_id") or "").strip()
    verdict = str(args.get("verdict") or "").strip()
    if not session_id or not verdict:
        raise ValueError("session_id and verdict are required")
    state = load_state()
    record = {
        "sessionId": session_id,
        "verdict": verdict,
        "source": str(args.get("source") or "multi-llm"),
        "summary": str(args.get("summary") or ""),
        "dissent": str(args.get("dissent") or ""),
        "at": _now(),
    }
    reviews = state.setdefault("reviews", [])
    reviews.append(record)
    sessions = state.setdefault("sessions", {})
    entry = sessions.setdefault(session_id, {"teamName": args.get("team_name") or "", "trail": [], "createdAt": _now()})
    entry["reviews"] = (entry.get("reviews") or [])[-19:]
    entry["reviews"].append({"verdict": verdict, "source": record["source"], "at": record["at"]})
    entry["updatedAt"] = _now()
    prune(state)
    save_state(state)
    return json.dumps({"ok": True, "archived": True, "sessionId": session_id}, ensure_ascii=False)


def tool_list_recent(args: dict) -> str:
    limit = max(1, min(int(args.get("limit") or 10), 50))
    state = load_state()
    sessions = state.get("sessions", {})
    ranked = sorted(sessions.items(), key=lambda kv: kv[1].get("updatedAt", 0), reverse=True)[:limit]
    items = [
        {
            "sessionId": sid,
            "teamName": entry.get("teamName", ""),
            "lastNode": (entry.get("trail") or [{}])[-1].get("node", ""),
            "nodeCount": len(entry.get("trail") or []),
            "reviewCount": len(entry.get("reviews") or []),
            "updatedAt": entry.get("updatedAt", 0),
        }
        for sid, entry in ranked
    ]
    return json.dumps({"ok": True, "sessions": items}, ensure_ascii=False)


def _now() -> int:
    return int(time.time() * 1000)


# ============================================================
# MCP plumbing (JSON-RPC 2.0 over line-delimited stdio)
# ============================================================

TOOLS_SPEC = [
    {
        "name": "record_node",
        "description": "Record a lifecycle node transition for a team session (e.g. 方案→实现→验证).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string", "description": "Current session id"},
                "node": {"type": "string", "description": "Node name just completed or entered"},
                "detail": {"type": "string", "description": "Optional one-line detail"},
                "team_name": {"type": "string", "description": "Optional team name for context"},
            },
            "required": ["session_id", "node"],
        },
    },
    {
        "name": "get_status",
        "description": "Query node trail and archived reviews for a session (resumes orchestration state across compaction).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
            },
            "required": ["session_id"],
        },
    },
    {
        "name": "archive_review",
        "description": "Archive a review verdict (multi-llm ask_all result or human decision) for a session.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
                "verdict": {"type": "string", "description": "e.g. approved / rejected / needs-changes"},
                "source": {"type": "string", "description": "multi-llm / human / qa"},
                "summary": {"type": "string", "description": "One-paragraph rationale"},
                "dissent": {"type": "string", "description": "Dissenting opinions worth keeping"},
                "team_name": {"type": "string"},
            },
            "required": ["session_id", "verdict"],
        },
    },
    {
        "name": "list_recent",
        "description": "List recent orchestrated sessions with their last node and review counts.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "minimum": 1, "maximum": 50, "default": 10},
            },
        },
    },
]

TOOL_HANDLERS = {
    "record_node": tool_record_node,
    "get_status": tool_get_status,
    "archive_review": tool_archive_review,
    "list_recent": tool_list_recent,
}


def dispatch(msg: dict) -> dict | None:
    method = msg.get("method", "")
    msg_id = msg.get("id")
    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": SERVER_INFO,
            },
        }
    if method == "notifications/initialized":
        return None
    if method == "ping":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": TOOLS_SPEC}}
    if method == "tools/call":
        params = msg.get("params") or {}
        name = params.get("name")
        handler = TOOL_HANDLERS.get(name)
        if handler is None:
            return _error(msg_id, -32602, f"unknown tool: {name}")
        try:
            text = handler(params.get("arguments") or {})
        except Exception as exc:
            return {
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "content": [{"type": "text", "text": f"error: {exc}"}],
                    "isError": True,
                },
            }
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"content": [{"type": "text", "text": text}]}}
    if msg_id is not None:
        return _error(msg_id, -32601, f"method not found: {method}")
    return None


def _error(msg_id, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}


def main() -> None:
    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            continue
        try:
            reply = dispatch(msg)
        except Exception as exc:  # never crash the loop
            reply = _error(msg.get("id"), -32603, f"internal error: {exc}")
        if reply is not None:
            sys.stdout.write(json.dumps(reply, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
