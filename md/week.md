{"sessionId":16903,"dataScope":{"tableUidList":[],"chatBITopicList":[],"chatDatasetInfoList":[],"assetsList":[],"dataMart":[]},"question":"What's the order-level GMV for the last 7 days?","richText":"{\"root\":{\"children\":[{\"children\":[{\"detail\":0,\"format\":0,\"mode\":\"normal\",\"style\":\"\",\"text\":\"What's the order-level GMV for the last 7 days?\",\"type\":\"text\",\"version\":1}],\"direction\":\"ltr\",\"format\":\"start\",\"indent\":0,\"type\":\"paragraph\",\"version\":1,\"textFormat\":0,\"textStyle\":\"\"}],\"direction\":\"ltr\",\"format\":\"\",\"indent\":0,\"type\":\"root\",\"version\":1}}","extendContext":"","askAgain":false,"commonInfo":{"user":"zhilong.zhang","region":"REG","userEmail":"zhilong.zhang@shopee.com"}}

https://datasuite.staging.shopee.io/assistant/open/commonchat/chat/stream/[stress]()



{"type":"CommonChat","sessionName":"What's the order-level GMV for the last 7 days?","sessionScope":{"tableUidList":[],"chatBITopicList":[],"chatDatasetInfoList":[],"assetsList":[],"dataMart":[]},"commonInfo":{"user":"zhilong.zhang","region":"REG","userEmail":"zhilong.zhang@shopee.com"}}



{"sessionId":18264,"dataScope":{"tableUidList":[],"chatBITopicList":[],"chatDatasetInfoList":[],"assetsList":[],"dataMart":[]},"question":"What's the order-level GMV for the last 7 days?","richText":"{\"root\":{\"children\":[{\"children\":[{\"detail\":0,\"format\":0,\"mode\":\"normal\",\"style\":\"\",\"text\":\"What's the order-level GMV for the last 7 days?\",\"type\":\"text\",\"version\":1}],\"direction\":\"ltr\",\"format\":\"start\",\"indent\":0,\"type\":\"paragraph\",\"version\":1,\"textFormat\":0,\"textStyle\":\"\"}],\"direction\":\"ltr\",\"format\":\"\",\"indent\":0,\"type\":\"root\",\"version\":1}}","extendContext":"","askAgain":false,"commonInfo":{"user":"zhilong.zhang","region":"REG","userEmail":"zhilong.zhang@shopee.com"}}



{"type":"CommonChat","sessionName":"how to create a csv2hive task by DataHub?","sessionScope":{"tableUidList":[],"chatBITopicList":[],"chatDatasetInfoList":[],"assetsList":[],"dataMart":[]},"commonInfo":{"user":"zhilong.zhang","region":"REG","userEmail":"zhilong.zhang@shopee.com"}}

https://datasuite.staging.shopee.io/assistant/session/new

{"sessionId":18202,"dataScope":{"tableUidList":[],"chatBITopicList":[],"chatDatasetInfoList":[],"assetsList":[],"dataMart":[]},"question":"how to create a csv2hive task by DataHub","richText":"{\"root\":{\"children\":[{\"children\":[{\"detail\":0,\"format\":0,\"mode\":\"normal\",\"style\":\"\",\"text\":\"how to create a csv2hive task by DataHub\",\"type\":\"text\",\"version\":1}],\"direction\":\"ltr\",\"format\":\"start\",\"indent\":0,\"type\":\"paragraph\",\"version\":1,\"textFormat\":0,\"textStyle\":\"\"}],\"direction\":\"ltr\",\"format\":\"\",\"indent\":0,\"type\":\"root\",\"version\":1}}","extendContext":"","askAgain":false,"commonInfo":{"user":"zhilong.zhang","region":"REG","userEmail":"zhilong.zhang@shopee.com"}}



```
#!/usr/bin/env python3
"""
Simple SSE stress tester for the Datasuite assistant stream endpoint.

Features:
- Configurable concurrency and total connection count (hardcoded defaults).
- Uses hardcoded default URL / payload (no file dependency).
- Prints every SSE event along with timestamps and extracted trace ids.
- Records per-connection metrics for quick post-run inspection.
"""

import argparse
import asyncio
import json
import signal
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional
import copy

import aiohttp

DEFAULT_URL = (
    "https://datasuite.staging.shopee.io/assistant/open/commonchat/chat/stream/stress"
)

DEFAULT_PAYLOAD: Dict[str, Any] = {
    "type": "CommonChat",
    "sessionName": "每天有多少会话数test",
    "sessionScope": {
        "tableUidList": [],
        "chatBITopicList": [],
        "chatDatasetInfoList": [],
        "assetsList": [],
        "dataMart": [],
    },

    "dataScope": {
        "tableUidList": [
            "SG.data_infra.shopee_di_rag_db__chat_message_tab__reg_continuous_s0_live"
        ],
        "chatBITopicList": [],
        "chatDatasetInfoList": [],
        "assetsList": [],
        "dataMart": [],
    },
    "question": "每天有多少会话数",
    "richText": '{"root":{"children":[{"children":[{"detail":0,"format":0,"mode":"normal","style":"","text":"每天有多少会话数","type":"text","version":1}],"direction":"ltr","format":"","indent":0,"type":"paragraph","version":1,"textFormat":0,"textStyle":""}],"direction":"ltr","format":"","indent":0,"type":"root","version":1}}',
    "askAgain": False,
    "extendContext": "",
    "commonInfo": {
        "user": "yiming.feng",
        "region": "REG",
        "userEmail": "yiming.feng@shopee.com",
    },
}
DEFAULT_LOG_FILE = Path(
    f"/Users/zhilong.zhang/PycharmProjects/PythonProject/code/stress_test/stress_{time.strftime('%Y%m%d_%H%M%S')}.log"
)
DEFAULT_REQUESTS = 20
DEFAULT_CONCURRENCY = 50
DEFAULT_MAX_EVENTS = -1
DEFAULT_SOCK_CONNECT_TIMEOUT = 60.0
DEFAULT_HEADER_ITEMS: List[str] = []


def make_logger(log_path: Path):
    """Create a logger that prints to stdout and appends to a log file."""
    log_path.parent.mkdir(parents=True, exist_ok=True)

    def _log(message: str) -> None:
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
        line = f"[{timestamp}] {message}"
        print(line)
        try:
            with log_path.open("a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:
            # 如果写日志失败，至少保证控制台输出
            pass

    return _log


def load_payload(args: argparse.Namespace) -> Dict[str, Any]:
    if args.payload_inline:
        return json.loads(args.payload_inline)
    if args.payload_file:
        return json.loads(Path(args.payload_file).read_text(encoding="utf-8"))
    # fallback to hardcoded default
    return DEFAULT_PAYLOAD


def resolve_url(args: argparse.Namespace) -> str:
    return DEFAULT_URL


def parse_headers(header_items: List[str]) -> Dict[str, str]:
    headers: Dict[str, str] = {}
    for item in header_items:
        if ":" not in item:
            raise ValueError(f"Header 需要使用 key:value 格式, 当前: {item}")
        key, value = item.split(":", 1)
        headers[key.strip()] = value.strip()
    return headers


@dataclass
class SSERecord:
    conn_id: int
    start_ts: float
    end_ts: Optional[float] = None
    event_count: int = 0
    trace_ids: set[str] = field(default_factory=set)
    error: Optional[str] = None

    def finish(self) -> None:
        self.end_ts = time.time()

    def to_summary(self) -> Dict[str, Any]:
        return {
            "conn_id": self.conn_id,
            "start": self.start_ts,
            "end": self.end_ts,
            "cost_time": (self.end_ts - self.start_ts) if self.end_ts else None,
            "events": self.event_count,
            "trace_ids": list(self.trace_ids),
            "error": self.error,
        }


def extract_trace(event_payload: str) -> Optional[str]:
    try:
        parsed = json.loads(event_payload)
    except json.JSONDecodeError:
        return None
    for key in ("trace_id", "traceId", "traceID", "trace"):
        if isinstance(parsed, dict) and key in parsed:
            return str(parsed[key])
    return None


async def consume_sse(
    conn_id: int,
    session: aiohttp.ClientSession,
    args: argparse.Namespace,
    payload: Dict[str, Any],
    semaphore: asyncio.Semaphore,
    log,
) -> SSERecord:
    record = SSERecord(conn_id=conn_id, start_ts=time.time())
    async with semaphore:
        sid = payload.get("sessionId")
        log(
            f"[Conn {conn_id}] 开始 {time.strftime('%H:%M:%S', time.localtime(record.start_ts))} | sessionId={sid}"
        )
        try:
            async with session.post(
                args.url,
                json=payload,
                timeout=aiohttp.ClientTimeout(total=None, sock_read=None),
                headers=args.headers,
            ) as resp:
                resp.raise_for_status()
                buffer = ""
                async for chunk in resp.content.iter_chunked(1024 * 1024):
                    if not chunk:
                        break
                    decoded = chunk.decode("utf-8", errors="ignore")
                    buffer += decoded
                    print(buffer)
                    # 按 SSE 规范，事件之间以空行分隔
                    while "\n\n" in buffer:
                        event, buffer = buffer.split("\n\n", 1)
                        lines = event.splitlines()
                        data_lines: List[str] = []
                        for line in lines:
                            line = line.strip()
                            if not line:
                                continue
                            if line.startswith("data:"):
                                data_lines.append(line[5:].strip())
                            else:
                                log(f"[Conn {conn_id}] meta: {line}")
                        if data_lines:
                            data = "\n".join(data_lines)
                            record.event_count += 1
                            trace_id = extract_trace(data)
                            if trace_id:
                                record.trace_ids.add(trace_id)
                            log(f"[Conn {conn_id}] event #{record.event_count}: {data}")
                            if "Something went wrong" in data:
                                log(
                                    f"[Conn {conn_id}] ERROR_EVENT: payload={payload} | event={data}"
                                )
                            if 0 < args.max_events <= record.event_count:
                                break
        except Exception as exc:  # pylint: disable=broad-except
            record.error = repr(exc)
            log(f"[Conn {conn_id}] 发生异常: {exc}")
        finally:
            record.finish()
            log(
                f"[Conn {conn_id}] 结束 {time.strftime('%H:%M:%S', time.localtime(record.end_ts))} "
                f"耗时 {record.end_ts - record.start_ts:.2f}s | events={record.event_count}"
            )
    return record


async def runner(args: argparse.Namespace, log) -> None:
    payload_template = load_payload(args)
    args.url = resolve_url(args)
    args.headers = parse_headers(DEFAULT_HEADER_ITEMS)
    args.requests = DEFAULT_REQUESTS
    args.concurrency = min(DEFAULT_CONCURRENCY, args.requests)
    args.max_events = DEFAULT_MAX_EVENTS
    args.sock_connect_timeout = DEFAULT_SOCK_CONNECT_TIMEOUT

    semaphore = asyncio.Semaphore(args.concurrency)
    timeout = aiohttp.ClientTimeout(
        total=None, sock_connect=args.sock_connect_timeout, sock_read=None
    )

    async with aiohttp.ClientSession(timeout=timeout) as session:
        tasks = [
            asyncio.create_task(
                consume_sse(
                    i + 1,
                    session,
                    args,
                    copy.deepcopy(payload_template),
                    semaphore,
                    log,
                )
            )
            for i in range(args.requests)
        ]
        results = await asyncio.gather(*tasks)

    log("\n==== 汇总 ====")
    for item in results:
        log(str(item.to_summary()))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="SSE 压测工具")
    parser.add_argument("--payload-file", type=str, help="直接读取 JSON payload 的文件")
    parser.add_argument("--payload-inline", type=str, help="直接传入 JSON 字符串")
    parser.add_argument(
        "--log-file",
        type=Path,
        default=DEFAULT_LOG_FILE,
        help="日志文件路径，默认写入 /Users/zhilong.zhang/PycharmProjects/PythonProject/code/stress_test/stress.log",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    logger = make_logger(args.log_file)

    loop = asyncio.get_event_loop()

    def handle_stop(signame: str) -> None:
        logger(f"收到 {signame}，准备退出...")
        for task in asyncio.all_tasks(loop):
            task.cancel()

    for signame in ("SIGINT", "SIGTERM"):
        if hasattr(signal, signame):
            loop.add_signal_handler(
                getattr(signal, signame), lambda s=signame: handle_stop(s)
            )

    try:
        loop.run_until_complete(runner(args, logger))
    except asyncio.CancelledError:
        logger("任务被取消")
    finally:
        loop.close()


if __name__ == "__main__":
    main()
```





```
cd /usr/local/redis-7.0.15
redis-server
redis-cli shutdown
ps aux | grep redis-server
sudo kill -9 

brew services start mysql
brew services stop mysql


cd /usr/local/etc
bin/zookeeper-server-start.sh config/zookeeper.properties 
bin/kafka-server-start.sh config/server.properties
# 创建topic
bin/kafka-topics.sh --create --topic test-events --bootstrap-server localhost:9092
# 查看状态
bin/kafka-topics.sh --describe --topic flash-sale-order --bootstrap-server localhost:9092
# 发送消息
bin/kafka-console-producer.sh --topic test-events --bootstrap-server localhost:9092
Hello, Kafka
This is my first enent


qps 1500

```



```
============================================================
📊 压测结果统计 [直接数据库（异步/虚拟线程）]
============================================================

📈 总体统计:
   总请求数: 100
   成功数量: 70 (70.0%)
   失败数量: 30 (30.0%)
   总耗时: 0.71秒
   QPS: 141.72

⏱️ 响应时间(ms):
   平均: 572.89
   最小: 339.16
   最大: 703.36
   P50: 598.11
   P95: 698.14
   P99: 703.36

❌ 失败原因分布:
   系统繁忙，请重试: 30次

✅ 成功抢购用户: 70人
   用户2: FS2005839390672302080
   用户3: FS2005839389455953920
   用户4: FS2005839389728583680
   用户6: FS2005839390802325504
   用户7: FS2005839389518868480
   ... 共70人

============================================================





============================================================
📊 压测结果统计 [直接数据库（同步）]
============================================================

📈 总体统计:
   总请求数: 100
   成功数量: 4 (4.0%)
   失败数量: 96 (96.0%)
   总耗时: 1.51秒
   QPS: 66.41

⏱️ 响应时间(ms):
   平均: 797.43
   最小: 129.62
   最大: 1504.02
   P50: 677.94
   P95: 1456.84
   P99: 1504.02

❌ 失败原因分布:
   系统繁忙，请重试: 96次

✅ 成功抢购用户: 4人
   用户57: FS2005841893979791360
   用户64: FS2005841896274075648
   用户78: FS2005841892197212160
   用户83: FS2005841892989935616

============================================================


============================================================
📊 压测结果统计 [直接数据库（异步/虚拟线程）]
============================================================

📈 总体统计:
   总请求数: 100
   成功数量: 25 (25.0%)
   失败数量: 75 (75.0%)
   总耗时: 0.44秒
   QPS: 229.58

⏱️ 响应时间(ms):
   平均: 388.37
   最小: 159.03
   最大: 433.47
   P50: 414.54
   P95: 432.20
   P99: 433.47

❌ 失败原因分布:
   系统繁忙，请重试: 72次
   超出限购数量: 3次

✅ 成功抢购用户: 25人
   用户4: FS2005844293796913152
   用户6: FS2005844294270869504
   用户9: FS2005844294451224576
   用户14: FS2005844294300229632
   用户18: FS2005844293704638464
   ... 共25人

============================================================


============================================================
📊 压测结果统计 [Redis + Lua + Kafka（异步/虚拟线程）]
============================================================

📈 总体统计:
   总请求数: 3000
   成功数量: 3000 (100.0%)
   失败数量: 0 (0.0%)
   总耗时: 1.98秒
   QPS: 1514.60

⏱️ 响应时间(ms):
   平均: 1665.17
   最小: 1074.95
   最大: 1959.07
   P50: 1728.74
   P95: 1903.43
   P99: 1943.60

✅ 成功抢购用户: 3000人
   用户1: FS2005845077880102916
   用户2: FS2005845075665510405
   用户3: FS2005845078370836487
   用户4: FS2005845077741690884
   用户5: FS2005845077930434562
   ... 共3000人

============================================================



============================================================
📊 压测结果统计 [Redis + Lua + Kafka（同步）]
============================================================

📈 总体统计:
   总请求数: 3000
   成功数量: 3000 (100.0%)
   失败数量: 0 (0.0%)
   总耗时: 1.64秒
   QPS: 1826.65

⏱️ 响应时间(ms):
   平均: 1441.71
   最小: 1059.11
   最大: 1634.63
   P50: 1475.00
   P95: 1582.34
   P99: 1627.13

✅ 成功抢购用户: 3000人
   用户1: FS2005845373393985544
   用户2: FS2005845373427539996
   用户3: FS2005845373565952003
   用户4: FS2005845373524008984
   用户5: FS2005845373452705815
   ... 共3000人

============================================================
```

