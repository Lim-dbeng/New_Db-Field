const http = require('http');
const fs = require('fs');
const path = require('path');

const dir = __dirname;
const PORT = 3456;

const MIME = {
  '.html': 'text/html',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.css': 'text/css',
  '.js': 'application/javascript',
  '.json': 'application/json',
};

http.createServer((req, res) => {
  let filePath = path.join(dir, req.url === '/' ? 'generate-app-icons.html' : req.url);
  const ext = path.extname(filePath);
  const contentType = MIME[ext] || 'application/octet-stream';

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(data);
  });
}).listen(PORT, () => {
  console.log(`Icon preview server running at http://localhost:${PORT}`);
});
