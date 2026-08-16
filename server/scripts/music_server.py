#!/usr/bin/env python3
"""Simple static file server for serving transcoded MP3 files to AllMusic clients."""
import http.server
import os

PORT = 8090
DIR = '/home/minecraft/music_cache'

os.chdir(DIR)
handler = http.server.SimpleHTTPRequestHandler
httpd = http.server.ThreadingHTTPServer(('0.0.0.0', PORT), handler)
print('Music HTTP server on port %d, serving %s' % (PORT, DIR))
httpd.serve_forever()
