"""Мост между приложением Android и оригинальным TG WS Proxy (папка proxy/).

Запуск и остановка устроены так же, как в трей-приложении для компьютера: прокси работает
в отдельном потоке со своим циклом asyncio, остановка идёт через asyncio.Event.
Журнал оригинального движка пересылается в приложение через объект sink (интерфейс PyLogSink).
"""
import asyncio
import logging
import threading

from proxy import tg_ws_proxy as core
from proxy.config import coerce_domain_list, parse_dc_ip_list, proxy_config
from proxy.utils import DomainCensorFilter

_lock = threading.Lock()
_thread = None
_loop = None
_stop = None


class _SinkHandler(logging.Handler):
    def __init__(self, sink):
        super().__init__()
        self._sink = sink

    def emit(self, record):
        try:
            self._sink.log(self.format(record))
        except Exception:
            pass


def _split(text):
    return [part.strip() for part in str(text or "").replace(";", ",").split(",") if part.strip()]


def start(sink, port, secret, dc_ip, buf_kb, pool_size, cfproxy, h2,
          user_domains, worker_domains, no_secure, verbose):
    global _thread
    with _lock:
        if _thread is not None and _thread.is_alive():
            return

        proxy_config.port = int(port)
        proxy_config.host = "127.0.0.1"
        proxy_config.secret = str(secret)
        proxy_config.dc_redirects = parse_dc_ip_list(_split(dc_ip))
        proxy_config.buffer_size = max(4, int(buf_kb)) * 1024
        proxy_config.pool_size = max(0, int(pool_size))
        proxy_config.fallback_cfproxy = bool(cfproxy)
        proxy_config.cfproxy_user_domains = coerce_domain_list(_split(user_domains))
        proxy_config.cfproxy_worker_domains = coerce_domain_list(_split(worker_domains))
        proxy_config.cfproxy_h2_media = bool(h2)
        proxy_config.disable_secure = bool(no_secure)
        proxy_config.fake_tls_domain = ""
        proxy_config.proxy_protocol = False
        proxy_config.force_test_dc = False

        root = logging.getLogger()
        for handler in list(root.handlers):
            if isinstance(handler, _SinkHandler):
                root.removeHandler(handler)
        handler = _SinkHandler(sink)
        handler.setFormatter(logging.Formatter("%(levelname)-5s  %(message)s"))
        handler.addFilter(DomainCensorFilter())
        root.addHandler(handler)
        root.setLevel(logging.DEBUG if verbose else logging.INFO)
        for name in ("asyncio", "httpx", "httpcore", "h2", "hpack"):
            logging.getLogger(name).setLevel(logging.WARNING)

        _thread = threading.Thread(target=_run_thread, args=(sink,), name="tgws-proxy", daemon=True)
        _thread.start()


def _run_thread(sink):
    global _loop, _stop
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    stop = asyncio.Event()
    _loop, _stop = loop, stop
    error = None
    try:
        loop.run_until_complete(core._run(stop_event=stop))
    except BaseException as exc:  # noqa: BLE001 - любая ошибка должна дойти до приложения
        error = "%s: %s" % (type(exc).__name__, exc)
        logging.getLogger("tgws-android").exception("прокси остановился с ошибкой")
    finally:
        try:
            loop.close()
        except Exception:
            pass
        _loop = _stop = None
        try:
            sink.onStopped(error)
        except Exception:
            pass


def stop():
    loop, stop_event = _loop, _stop
    if loop is not None and stop_event is not None:
        try:
            loop.call_soon_threadsafe(stop_event.set)
        except RuntimeError:
            pass


def is_running():
    return _thread is not None and _thread.is_alive()
