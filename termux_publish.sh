#!/data/data/com.termux/files/usr/bin/bash
# Run inside the Jarvis folder in Termux:  bash termux_publish.sh
set -e
pkg install -y git gh
gh auth status >/dev/null 2>&1 || gh auth login
git init -b main 2>/dev/null || true
git add .
git commit -m "Jarvis v1.0.0" || true
gh repo create Jarvis --public --source=. --push || git push -u origin main
git tag -f v1.0.0
git push -f origin v1.0.0
echo "Done. In a few minutes, download Jarvis.apk from the repo's Releases page."
