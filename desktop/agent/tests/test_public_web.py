import socket
import unittest
from desktop.agent.public_web import MAX_BODY, destination, read


def resolver(*addresses):
    return lambda *a, **k: [(socket.AF_INET, socket.SOCK_STREAM, 6, '', (ip, 443)) for ip in addresses]


class PublicWebTests(unittest.TestCase):
    def test_private_tailnet_metadata_loopback_and_ipv6_are_rejected(self):
        for ip in ['127.0.0.1', '192.168.1.1', '10.0.0.1', '172.16.1.1', '100.64.0.1',
                   '169.254.169.254', '::1', 'fd00::1', 'fe80::1', '::ffff:8.8.8.8', '224.0.0.1']:
            with self.subTest(ip=ip), self.assertRaises(ValueError): destination('https://example.com', resolver(ip))

    def test_mixed_public_private_answers_and_private_names_are_blocked(self):
        with self.assertRaises(ValueError): destination('https://example.com', resolver('8.8.8.8', '10.0.0.1'))
        for url in ['https://localhost', 'https://host.local', 'https://pc.ts.net', 'http://example.com',
                    'https://example.com:8443', 'https://user:password@example.com', 'https://example.com\\x']:
            with self.subTest(url=url), self.assertRaises(ValueError): destination(url, resolver('8.8.8.8'))

    def test_connection_uses_validated_ip_and_no_credentials(self):
        calls = []
        class Connection:
            def __init__(self, host, ip): calls.append((host, ip))
            def request(self, method, path, headers): calls.append((method, path, headers))
            def getresponse(self): return Response()
            def close(self): pass
        result = read('https://example.com/page?q=1', resolver('8.8.8.8'), Connection)
        self.assertEqual(calls[0], ('example.com', '8.8.8.8'))
        self.assertEqual(calls[1][0], 'GET')
        self.assertNotIn('Authorization', calls[1][2])
        self.assertNotIn('Cookie', calls[1][2])
        self.assertTrue(result['untrusted'])

    def test_redirect_is_revalidated_before_second_connection(self):
        calls = []
        class Connection:
            def __init__(self, host, ip): calls.append(ip)
            def request(self, *a, **k): pass
            def getresponse(self): return Response(302, {'Location': 'https://127.0.0.1/secret'})
            def close(self): pass
        def resolve(host, *a, **k): return resolver('127.0.0.1' if host == '127.0.0.1' else '8.8.8.8')()
        with self.assertRaises(ValueError): read('https://example.com', resolve, Connection)
        self.assertEqual(calls, ['8.8.8.8'])

    def test_body_and_compression_are_bounded(self):
        for response in [Response(body=b'x' * (MAX_BODY + 1)), Response(headers={'Content-Encoding': 'gzip'})]:
            class Connection:
                def __init__(self, *a): pass
                def request(self, *a, **k): pass
                def getresponse(self): return response
                def close(self): pass
            with self.assertRaises(ValueError): read('https://example.com', resolver('8.8.8.8'), Connection)

    def test_wire_deadline_stops_slow_reads_without_an_unbounded_socket_timeout(self):
        now=[0];closed=[]
        class Connection:
            def __init__(self,*args):pass
            def request(self,*args,**kwargs):now[0]=46
            def getresponse(self):return Response()
            def close(self):closed.append(True)
        with self.assertRaises(TimeoutError):read('https://example.com',resolver('8.8.8.8'),Connection,clock=lambda:now[0])
        self.assertEqual(closed,[True])


class Response:
    def __init__(self, status=200, headers=None, body=b'<p>Page</p>'):
        self.position=0
        self.status, self.headers, self.body = status, {'Content-Type': 'text/html', **(headers or {})}, body
    def getheader(self, key, default=None): return self.headers.get(key, default)
    def read(self, amount):
        value=self.body[self.position:self.position+amount];self.position+=len(value);return value
    def read1(self, amount):return self.read(amount)
