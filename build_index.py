# Builds index.html (a full page) from the app fragment, adding the web-app manifest and service worker.
import sys
src=open(sys.argv[1]).read()
head='''<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#000000">
<link rel="manifest" href="manifest.webmanifest">
<link rel="icon" href="icons/icon-192.png">
<link rel="apple-touch-icon" href="icons/icon-192.png">
</head><body>
'''
tail='''
<script>if("serviceWorker" in navigator && location.protocol==="https:" && !/appassets\\.androidplatform\\.net/.test(location.host)) navigator.serviceWorker.register("sw.js").catch(()=>{});</script>
</body></html>
'''
open(sys.argv[2],'w').write(head+src+tail)
