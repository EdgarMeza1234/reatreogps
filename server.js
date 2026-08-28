const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');

const DB_FILE = process.env.DB_FILE || path.join(__dirname, 'rastreogps.db');
const PUBLIC_DIR = path.join(__dirname, 'public');
const PORT = process.env.PORT || 3010;
const ONLINE_WINDOW_MS = 60000; // 60s sin reportes = no esta en linea

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
};

const db = new DatabaseSync(DB_FILE);
db.exec(`
  CREATE TABLE IF NOT EXISTS devices (
    name TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS points (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device TEXT NOT NULL,
    lat REAL NOT NULL,
    lng REAL NOT NULL,
    acc REAL,
    created_at TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_points_device ON points(device, created_at);
`);

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
  res.end(body);
}

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const raw = Buffer.concat(chunks).toString('utf8');
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { return {}; }
}

function cleanDevice(name) {
  return String(name || '').trim().slice(0, 50);
}

function validLatLng(lat, lng) {
  return typeof lat === 'number' && typeof lng === 'number' &&
    lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
}

async function handleApi(req, res, url) {
  if (req.method === 'POST' && url.pathname === '/api/points') {
    const body = await readBody(req);
    const device = cleanDevice(body.device);
    const lat = Number(body.lat);
    const lng = Number(body.lng);
    const acc = body.acc !== undefined ? Number(body.acc) : null;
    if (!device || !validLatLng(lat, lng)) {
      return sendJson(res, 400, { error: 'Datos invalidos' });
    }
    const now = new Date().toISOString();
    db.prepare('INSERT OR IGNORE INTO devices (name, created_at) VALUES (?, ?)').run(device, now);
    db.prepare('INSERT INTO points (device, lat, lng, acc, created_at) VALUES (?, ?, ?, ?, ?)')
      .run(device, lat, lng, Number.isFinite(acc) ? acc : null, now);
    return sendJson(res, 200, { ok: true, device, at: now });
  }

  if (req.method === 'GET' && url.pathname === '/api/devices') {
    const last = db.prepare(`
      SELECT p.device, p.lat, p.lng, p.acc, p.created_at AS last_at,
             (SELECT COUNT(*) FROM points p2 WHERE p2.device = p.device) AS puntos
      FROM points p
      WHERE p.id = (SELECT MAX(id) FROM points p3 WHERE p3.device = p.device)
      ORDER BY p.device
    `).all();
    const now = Date.now();
    const devices = last.map(d => ({
      name: d.device,
      lat: d.lat,
      lng: d.lng,
      acc: d.acc,
      last_at: d.last_at,
      puntos: d.puntos,
      online: now - Date.parse(d.last_at) < ONLINE_WINDOW_MS
    }));
    return sendJson(res, 200, { devices });
  }

  if (req.method === 'GET' && url.pathname === '/api/points') {
    const device = cleanDevice(url.searchParams.get('device'));
    const from = url.searchParams.get('from');
    const to = url.searchParams.get('to');
    if (!device) return sendJson(res, 400, { error: 'Falta device' });
    let sql = 'SELECT id, device, lat, lng, acc, created_at FROM points WHERE device = ?';
    const params = [device];
    if (from) { sql += ' AND created_at >= ?'; params.push(from); }
    if (to) { sql += ' AND created_at <= ?'; params.push(to); }
    sql += ' ORDER BY id ASC';
    const points = db.prepare(sql).all(...params);
    return sendJson(res, 200, { device, points });
  }

  return sendJson(res, 404, { error: 'No existe' });
}

function serveStatic(res, pathname) {
  let rel = pathname === '/' ? '/index.html' : pathname;
  if (!path.extname(rel)) rel += '.html';
  const file = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!file.startsWith(PUBLIC_DIR)) {
    res.writeHead(403); res.end('Forbidden'); return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('No encontrado');
      return;
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
}

const requestHandler = async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname.startsWith('/api/')) {
    return handleApi(req, res, url);
  }
  serveStatic(res, url.pathname);
};

let server;
const KEY = path.join(__dirname, 'server.key');
const CERT = path.join(__dirname, 'server.crt');
if (fs.existsSync(KEY) && fs.existsSync(CERT)) {
  server = https.createServer({ key: fs.readFileSync(KEY), cert: fs.readFileSync(CERT) }, requestHandler);
} else {
  server = http.createServer(requestHandler);
}

server.listen(PORT, () => {
  const proto = fs.existsSync(KEY) && fs.existsSync(CERT) ? 'https' : 'http';
  console.log(`RastreoGPS escuchando en ${proto}://localhost:${PORT}`);
  console.log(`  Dashboard (mapa): ${proto}://localhost:${PORT}/`);
  console.log(`  Pagina del celular: ${proto}://localhost:${PORT}/tracker`);
  if (proto === 'http') {
    console.log('\nNOTA: para usar el GPS desde el CELULAR (no localhost) el navegador exige HTTPS.');
    console.log('      Genera un certificado con openssl y colocalo como server.key / server.crt');
  }
});