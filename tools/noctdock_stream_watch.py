#!/usr/bin/env python3
# Copyright Citra Emulator Project / Azahar Emulator Project
# Licensed under GPLv2 or any later version
# Refer to the license.txt file included in the NoctDock-Azahar repository root.
"""Watch NoctDock Azahar Stream Watch metrics from a laptop/Cursor session."""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen


def fetch_json(base_url: str, path: str, timeout: float = 2.0) -> dict:
    with urlopen(Request(f"{base_url}{path}", headers={"Accept": "application/json"}), timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def watch_sse(base_url: str):
    request = Request(f"{base_url}/watch", headers={"Accept": "text/event-stream"})
    with urlopen(request, timeout=5.0) as response:
        for raw_line in response:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if line.startswith("data:"):
                yield json.loads(line[5:].strip())


def format_metric(metrics: dict) -> str:
    health = metrics.get("streamHealth", "UNKNOWN")
    state = metrics.get("exportState", "UNKNOWN")
    resolution = metrics.get("exportResolution", "unknown")
    fps = metrics.get("actualExportFps", 0)
    target = metrics.get("targetFps", 0)
    readback = metrics.get("glReadPixelsAvgMs", 0)
    readback_max = metrics.get("glReadPixelsMaxMs", 0)
    queue = metrics.get("encoderQueueDepth", 0)
    drops = metrics.get("encoderQueueDrops", 0)
    packets = metrics.get("packetsSent", 0)
    send_errors = metrics.get("sendErrors", 0)
    recommendation = metrics.get("recommendation", "")
    return (
        f"[{datetime.now().strftime('%H:%M:%S')}] {health:9} state={state:24} "
        f"{resolution}@{target} actual={fps}fps readback={readback}/{readback_max}ms "
        f"queue={queue} drops={drops} packets={packets} sendErrors={send_errors}\n"
        f"  recommendation: {recommendation}"
    )


def write_reports(base_url: str, latest_metrics: dict) -> None:
    try:
        report = fetch_json(base_url, "/report")
    except URLError:
        report = {"latest": latest_metrics, "summary": {}, "events": []}

    Path("noctdock_stream_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    summary = report.get("summary", {})
    latest = report.get("latest", latest_metrics)
    lines = [
        "NoctDock Stream Watch Report",
        f"Generated: {datetime.now().isoformat(timespec='seconds')}",
        "",
        f"Health: {latest.get('streamHealth')}",
        f"State: {latest.get('exportState')}",
        f"Resolution: {latest.get('exportResolution')}",
        f"Target FPS: {latest.get('targetFps')}",
        f"Actual FPS: {latest.get('actualExportFps')}",
        f"Average readback: {summary.get('glReadPixelsAvgMs', latest.get('glReadPixelsAvgMs'))} ms",
        f"Max readback: {summary.get('glReadPixelsMaxMs', latest.get('glReadPixelsMaxMs'))} ms",
        f"Encoder drops: {summary.get('encoderQueueDrops', latest.get('encoderQueueDrops'))}",
        f"Send errors: {summary.get('sendErrors', latest.get('sendErrors'))}",
        "",
        f"Recommendation: {latest.get('recommendation')}",
        "",
        "Recent events:",
    ]
    for event in report.get("events", [])[-20:]:
        lines.append(f"- {event.get('type')}: {event.get('message')}")
    Path("noctdock_stream_report.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Watch NoctDock Azahar local stream metrics.")
    parser.add_argument("--host", required=True, help="Retroid/Azahar device IP address")
    parser.add_argument("--port", type=int, default=45456, help="Stream Watch port")
    parser.add_argument("--poll", action="store_true", help="Poll /metrics instead of using /watch SSE")
    args = parser.parse_args()

    base_url = f"http://{args.host}:{args.port}"
    latest: dict = {}

    try:
        health = fetch_json(base_url, "/health")
        print(f"Connected to Stream Watch: {health}")
        if args.poll:
            while True:
                latest = fetch_json(base_url, "/metrics")
                print(format_metric(latest), flush=True)
                time.sleep(1)
        else:
            for latest in watch_sse(base_url):
                print(format_metric(latest), flush=True)
    except KeyboardInterrupt:
        print("\nStopping watcher...")
    except (URLError, TimeoutError, json.JSONDecodeError) as error:
        print(f"Stream Watch connection failed: {error}", file=sys.stderr)
        if not latest:
            return 1
    finally:
        if latest:
            write_reports(base_url, latest)
            print("Saved noctdock_stream_report.json and noctdock_stream_report.txt")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
