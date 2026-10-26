"""Read-only HTTPS broker with DNS pinning and redirect revalidation.

Never forwards cookies, authorization or caller-supplied headers. The sandbox
itself has no IP network. A later IPC adapter exposes only these bounded reads.
"""
import http.client
import asyncio
import time
import ipaddress
import socket
import ssl
import zlib
from urllib.parse import urljoin, urlsplit

MAX_BODY = 2 * 1024 * 1024
# Publishers' CDNs often compress even when asked not to (python.org's release pages did), so gzip and deflate are
# accepted and decoded as they arrive; MAX_BODY bounds the decoded page, so a small compressed body can't expand past it.
ENCODINGS = {'gzip': 16 + zlib.MAX_WBITS, 'x-gzip': 16 + zlib.MAX_WBITS, 'deflate': zlib.MAX_WBITS}


class PageHTTPError(ValueError):
    """The publisher responded, but the requested page was not successful."""
    def __init__(self, status, url):
        self.status = status
        self.url = url
        super().__init__(f'Publisher returned HTTP {status}')


class PublicReadPool:
    """Process-wide cap shared by phone evidence reads and approved agent IPC."""
    def __init__(self):self.pending=set()
    async def fetch(self,reader,url,timeout):
        if len(self.pending)>=3:raise ValueError('Public reader capacity reached; retry later')
        job=asyncio.create_task(asyncio.to_thread(reader,url));self.pending.add(job)
        def complete(future):
            self.pending.discard(future)
            if not future.cancelled():future.exception()
        job.add_done_callback(complete)
        return await asyncio.wait_for(asyncio.shield(job),timeout)


READ_POOL=PublicReadPool()



def destination(url, resolve=socket.getaddrinfo):
    if not isinstance(url, str) or len(url) > 4096 or any(ord(c) < 33 for c in url) or '\\' in url:
        raise ValueError('Invalid URL')
    parsed = urlsplit(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError('Only public HTTPS on port 443 is allowed')
    host = parsed.hostname.encode('idna').decode('ascii').lower().rstrip('.')
    if host in {'localhost', 'localhost.localdomain'} or host.endswith(('.localhost', '.local', '.internal', '.ts.net')):
        raise ValueError('Private host is blocked')
    answers = resolve(host, 443, type=socket.SOCK_STREAM)
    addresses = {answer[4][0] for answer in answers}
    if not addresses: raise ValueError('Host has no addresses')
    for address in addresses:
        ip = ipaddress.ip_address(address)
        mapped = isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None
        if not ip.is_global or ip.is_multicast or ip.is_unspecified or mapped:
            raise ValueError('Non-public address is blocked')
    return host, sorted(addresses)[0], (parsed.path or '/') + ('?' + parsed.query if parsed.query else '')


class PinnedHTTPS(http.client.HTTPSConnection):
    def __init__(self, host, address):
        super().__init__(host, timeout=15, context=ssl.create_default_context())
        self.address = address

    def connect(self):
        raw = socket.create_connection((self.address, 443), timeout=self.timeout)
        try: self.sock = self._context.wrap_socket(raw, server_hostname=self.host)
        except BaseException:
            raw.close()
            raise


def inflate(decoder, chunk, room):
    """Decodes a compressed chunk, producing at most room bytes; a page that would decode to more is refused."""
    try:
        data = decoder.decompress(chunk, room) if chunk else decoder.flush()
    except zlib.error:
        raise ValueError('Compressed page could not be decoded') from None
    if decoder.unconsumed_tail or len(data) >= room:
        raise ValueError('Page exceeds 2 MB')
    return data


def read(url, resolve=socket.getaddrinfo, connection=PinnedHTTPS, clock=time.monotonic):
    deadline=clock()+45
    def remaining():
        value=deadline-clock()
        if value<=0:raise TimeoutError('Public read deadline exceeded')
        return min(15,value)
    for _ in range(6):
        host, address, path = destination(url, resolve)
        client = connection(host, address)
        try:
            client.timeout=remaining()
            client.request('GET', path, headers={'Host': host, 'Accept-Encoding': 'gzip, deflate, identity;q=0.5', 'User-Agent': 'FRIDAY-ReadOnly/1'})
            response = client.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader('Location')
                if not location: raise ValueError('Redirect lacks a destination')
                url = urljoin(url, location)
                continue
            if response.status != 200: raise PageHTTPError(response.status, url)
            mime = response.getheader('Content-Type', '').split(';')[0].strip().lower()
            if mime not in ('text/html', 'text/plain', 'application/xhtml+xml'):
                raise ValueError('Unsupported page type')
            encoding = (response.getheader('Content-Encoding', '') or 'identity').strip().lower()
            if encoding not in ('identity', *ENCODINGS):
                raise ValueError(f'Page uses unsupported {encoding} compression')
            decoder = zlib.decompressobj(ENCODINGS[encoding]) if encoding != 'identity' else None
            body=bytearray();wire=0
            while True:
                timeout=remaining()
                sock=getattr(client,'sock',None)
                if sock is not None:sock.settimeout(timeout)
                chunk=(response.read1 if hasattr(response,'read1') else response.read)(min(65536,MAX_BODY+1-wire))
                if not chunk:break
                wire+=len(chunk)
                if wire>MAX_BODY:raise ValueError('Page exceeds 2 MB')
                if decoder is not None:
                    chunk=inflate(decoder,chunk,MAX_BODY+1-len(body))
                body.extend(chunk)
                if len(body)>MAX_BODY:raise ValueError('Page exceeds 2 MB')
            if decoder is not None:
                body.extend(inflate(decoder,b'',MAX_BODY+1-len(body)))
                if len(body)>MAX_BODY:raise ValueError('Page exceeds 2 MB')
            return {'url': url, 'mime': mime, 'content': body.decode('utf-8', errors='replace'), 'untrusted': True}
        finally: client.close()
    raise ValueError('Too many redirects')
