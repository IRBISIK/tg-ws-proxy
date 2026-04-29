import asyncio
import json
import logging
import logging.handlers
import threading
from typing import Any, Dict

from proxy.config import parse_dc_ip_list, proxy_config
from proxy.tg_ws_proxy import _run

_thread = None
_loop = None
_stop_event = None
_state_lock = threading.Lock()
_running = False
_last_error = ""


def _set_running(value: bool) -> None:
    global _running
    with _state_lock:
        _running = value


def _set_error(value: str) -> None:
    global _last_error
    with _state_lock:
        _last_error = value


def _configure_logging(log_file: str, verbose: bool) -> None:
    root = logging.getLogger()
    root.handlers.clear()
    root.setLevel(logging.DEBUG if verbose else logging.INFO)

    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")

    console = logging.StreamHandler()
    console.setFormatter(fmt)
    root.addHandler(console)

    file_handler = logging.handlers.RotatingFileHandler(
        log_file,
        maxBytes=5 * 1024 * 1024,
        backupCount=1,
        encoding="utf-8",
    )
    file_handler.setFormatter(fmt)
    root.addHandler(file_handler)


def _apply_config(config: Dict[str, Any]) -> None:
    proxy_config.host = config.get("host", "127.0.0.1")
    proxy_config.port = int(config.get("port", 1443))
    proxy_config.secret = config.get("secret", "")
    proxy_config.dc_redirects = parse_dc_ip_list(config.get("dc_ip", []))
    proxy_config.buffer_size = int(config.get("buf_kb", 256)) * 1024
    proxy_config.pool_size = int(config.get("pool_size", 4))
    proxy_config.fallback_cfproxy = bool(config.get("cfproxy", True))
    proxy_config.fallback_cfproxy_priority = bool(config.get("cfproxy_priority", True))
    proxy_config.cfproxy_user_domain = config.get("cfproxy_user_domain", "")
    proxy_config.fake_tls_domain = config.get("fake_tls_domain", "")
    proxy_config.proxy_protocol = bool(config.get("proxy_protocol", False))


def start_proxy(config_json: str, log_file: str) -> bool:
    global _thread, _loop, _stop_event
    if is_running():
        return True

    config = json.loads(config_json)
    _configure_logging(log_file, bool(config.get("verbose", False)))
    _apply_config(config)
    _set_error("")

    def _runner() -> None:
        global _loop, _stop_event
        _set_running(True)
        try:
            _loop = asyncio.new_event_loop()
            asyncio.set_event_loop(_loop)
            _stop_event = asyncio.Event()
            _loop.run_until_complete(_run(_stop_event))
        except Exception as exc:
            _set_error(repr(exc))
            logging.getLogger("tg-mtproto-proxy").exception("Proxy failed")
        finally:
            _set_running(False)
            try:
                if _loop and not _loop.is_closed():
                    _loop.close()
            except Exception:
                pass
            _loop = None
            _stop_event = None

    _thread = threading.Thread(target=_runner, daemon=True, name="tgws-proxy")
    _thread.start()
    return True


def stop_proxy() -> bool:
    global _thread
    if not is_running():
        return True
    if _loop and _stop_event:
        _loop.call_soon_threadsafe(_stop_event.set)
    if _thread:
        _thread.join(timeout=8)
    return not is_running()


def is_running() -> bool:
    with _state_lock:
        return _running


def get_status_json() -> str:
    with _state_lock:
        return json.dumps({"running": _running, "last_error": _last_error})
