#!/usr/bin/env python3
import json
import sys
import threading
import time
import uuid


SCENARIO = sys.argv[1] if len(sys.argv) > 1 else "happy-path"
REQUIRES_OPENAI_AUTH = True
ACCOUNT = None if SCENARIO == "logged-out" else {
    "type": "chatgpt",
    "email": "codex-router@example.test",
    "planType": "pro",
}
PENDING_LOGINS = {}
LOCK = threading.Lock()


def send(payload):
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def complete_login_later(login_id):
    def run():
        time.sleep(0.35)
        with LOCK:
            state = PENDING_LOGINS.get(login_id)
            if not state or state["status"] != "pending":
                return
            state["status"] = "completed"
            global ACCOUNT
            ACCOUNT = {
                "type": "chatgpt",
                "email": "codex-router@example.test",
                "planType": "pro",
            }
        send({
            "method": "account/login/completed",
            "params": {
                "loginId": login_id,
                "success": True,
                "error": None,
            },
        })

    thread = threading.Thread(target=run, daemon=True)
    thread.start()


def new_thread_result(params):
    thread_id = f"thread-{uuid.uuid4()}"
    cwd = params.get("cwd") if isinstance(params, dict) else None
    return thread_id, {
        "thread": {
            "id": thread_id,
            "preview": "",
            "ephemeral": True,
            "modelProvider": "openai",
            "createdAt": int(time.time()),
            "updatedAt": int(time.time()),
            "status": {"type": "idle"},
            "path": None,
            "cwd": cwd,
            "cliVersion": "0.111.0",
            "source": "code-router-test",
            "agentNickname": None,
            "agentRole": None,
            "gitInfo": None,
            "name": None,
            "turns": [],
        },
        "model": "codex",
        "modelProvider": "openai",
        "serviceTier": "fast",
        "cwd": cwd,
        "approvalPolicy": "never",
        "sandbox": {"type": "readOnly", "access": {"type": "fullAccess"}, "networkAccess": False},
        "reasoningEffort": "xhigh",
    }


def extract_prompt(params):
    pieces = []
    for item in params.get("input", []):
        if item.get("type") == "text":
            pieces.append(item.get("text", ""))
    return "\n".join(pieces)


def stream_turn(thread_id, turn_id, prompt):
    def run():
        if "LONG_RUNNING" in prompt:
            time.sleep(1.4)

        send({
            "method": "turn/started",
            "params": {
                "threadId": thread_id,
                "turn": {"id": turn_id, "items": [], "status": "inProgress", "error": None},
            },
        })

        text = "Hello from fake Codex."
        if "STREAM_VARIANT" in prompt:
            text = "Streaming reply from fake Codex."

        for chunk in [text[: len(text) // 2], text[len(text) // 2 :]]:
            send({
                "method": "agentMessageDelta",
                "params": {
                    "threadId": thread_id,
                    "turnId": turn_id,
                    "itemId": f"item-{turn_id}",
                    "delta": chunk,
                },
            })
            time.sleep(0.05)

        send({
            "method": "turn/completed",
            "params": {
                "threadId": thread_id,
                "turn": {
                    "id": turn_id,
                    "items": [],
                    "status": "completed",
                    "error": None,
                },
            },
        })

    thread = threading.Thread(target=run, daemon=True)
    thread.start()


for raw_line in sys.stdin:
    line = raw_line.strip()
    if not line:
        continue

    request = json.loads(line)
    request_id = request.get("id")
    method = request.get("method")
    params = request.get("params", {})

    if method == "initialize":
        send({"id": request_id, "result": {"userAgent": "code-router-test/0.0.0"}})
        continue

    if method == "account/read":
        send({
            "id": request_id,
            "result": {
                "account": ACCOUNT,
                "requiresOpenaiAuth": REQUIRES_OPENAI_AUTH,
            },
        })
        continue

    if method == "account/login/start":
        login_id = f"login-{uuid.uuid4()}"
        with LOCK:
            PENDING_LOGINS[login_id] = {"status": "pending"}
        send({
            "id": request_id,
            "result": {
                "type": "chatgpt",
                "loginId": login_id,
                "authUrl": f"https://example.test/device/{login_id}",
            },
        })
        complete_login_later(login_id)
        continue

    if method == "account/login/cancel":
        login_id = params["loginId"]
        with LOCK:
            state = PENDING_LOGINS.get(login_id)
            if state and state["status"] == "pending":
                state["status"] = "cancelled"
                send({"id": request_id, "result": {"status": "canceled"}})
            else:
                send({"id": request_id, "result": {"status": "notFound"}})
        continue

    if method == "account/logout":
        ACCOUNT = None
        send({"id": request_id, "result": {}})
        continue

    if method == "thread/start":
        thread_id, result = new_thread_result(params)
        send({"id": request_id, "result": result})
        send({"method": "thread/started", "params": {"thread": result["thread"]}})
        continue

    if method == "turn/start":
        thread_id = params["threadId"]
        turn_id = f"turn-{uuid.uuid4()}"
        prompt = extract_prompt(params)
        send({
            "id": request_id,
            "result": {
                "turn": {"id": turn_id, "items": [], "status": "inProgress", "error": None},
            },
        })
        stream_turn(thread_id, turn_id, prompt)
        continue

    send({
        "id": request_id,
        "error": {"code": -32601, "message": f"Unsupported method {method}"},
    })
