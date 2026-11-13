const CACHE='round-timer-v3';
const FILES=['./','index.html','manifest.webmanifest','icon-192.png','icon-512.png','icon-maskable.png','apple-touch-icon.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(FILES.map(f=>new Request(f,{cache:'reload'})))));self.skipWaiting();});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));});
self.addEventListener('fetch',e=>{
  const req=e.request; if(req.method!=='GET') return;
  const sameOrigin=new URL(req.url).origin===self.location.origin;
  // App pages: network first (always fresh when online), cached copy when offline
  if(req.mode==='navigate' || (sameOrigin && /\.(html|webmanifest|js)$|\/$/.test(new URL(req.url).pathname))){
    e.respondWith(fetch(req,{cache:'no-store'}).then(r=>{ if(r.ok){const cp=r.clone();caches.open(CACHE).then(c=>c.put(req,cp));} return r; })
      .catch(()=>caches.match(req).then(h=>h||caches.match('index.html'))));
    return;
  }
  // Everything else (icons, fonts): cache first
  e.respondWith(caches.match(req).then(hit=>hit||fetch(req).then(r=>{ if(r&&(r.ok||r.type==='opaque')){const cp=r.clone();caches.open(CACHE).then(c=>c.put(req,cp));} return r; })));
});
