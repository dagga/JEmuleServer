// Development server of the admin panel: `next dev` behind a small proxy that sends /api/* to a running JEmuleServer.
// The proxy rewrites Host and Origin to the server's, so that its Host and Origin checks accept the requests.
//
//   JEMULE_ADMIN_URL=http://127.0.0.1:8080 npm run dev     (then open http://localhost:3000/)
//
// The admin panel of that server must accept the machine running this script (adminAllowedIps, adminBindAddress).
import { spawn } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import { createRequire } from 'node:module';

const target = new URL(process.env.JEMULE_ADMIN_URL || 'http://127.0.0.1:8080');
const port = Number(process.env.PORT || 3000);
const nextPort = port + 1;
const nextBin = createRequire(import.meta.url).resolve('next/dist/bin/next');

const next = spawn(process.execPath, [nextBin, 'dev', '--port', String(nextPort), '--hostname', '127.0.0.1'], {
  stdio: 'inherit',
  env: { ...process.env, NEXT_TELEMETRY_DISABLED: '1' },
});
next.on('exit', (code) => process.exit(code ?? 0));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => next.kill(signal));

const proxy = http.createServer((req, res) => {
  const api = req.url.startsWith('/api/');
  const headers = { ...req.headers };
  if (api) {
    headers.host = target.host;
    if (headers.origin) headers.origin = target.origin;
    delete headers.referer;
  }
  const upstream = http.request(
    {
      host: api ? target.hostname : '127.0.0.1',
      port: api ? Number(target.port || 80) : nextPort,
      method: req.method,
      path: req.url,
      headers,
    },
    (answer) => {
      res.writeHead(answer.statusCode ?? 502, answer.headers);
      answer.pipe(res);
    },
  );
  upstream.on('error', (e) => {
    if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: (api ? 'JEmuleServer at ' + target.origin : 'next dev') + ' unreachable: ' + e.message }));
  });
  req.pipe(upstream);
});

// Hot reload of next dev goes through a WebSocket
proxy.on('upgrade', (req, socket, head) => {
  const upstream = net.connect(nextPort, '127.0.0.1', () => {
    let request = req.method + ' ' + req.url + ' HTTP/' + req.httpVersion + '\r\n';
    for (let i = 0; i < req.rawHeaders.length; i += 2) request += req.rawHeaders[i] + ': ' + req.rawHeaders[i + 1] + '\r\n';
    upstream.write(request + '\r\n');
    if (head.length) upstream.write(head);
    socket.pipe(upstream).pipe(socket);
  });
  upstream.on('error', () => socket.destroy());
  socket.on('error', () => upstream.destroy());
});

proxy.listen(port, () => {
  console.log('Admin panel in development: http://localhost:' + port + '/ (API of ' + target.origin + ')');
});
