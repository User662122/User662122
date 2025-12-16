name: WebRTC Recorder (Windows)

on:
  workflow_dispatch:

jobs:
  record:
    runs-on: windows-latest
    timeout-minutes: 30

    steps:
      # 1️⃣ Checkout repo
      - uses: actions/checkout@v4

      # 2️⃣ Cache FFmpeg
      - name: Cache FFmpeg
        id: cache-ffmpeg
        uses: actions/cache@v4
        with:
          path: C:\ProgramData\chocolatey\bin
          key: ${{ runner.os }}-ffmpeg-${{ hashFiles('**/.github/workflows/*.yml') }}
          restore-keys: |
            ${{ runner.os }}-ffmpeg-

      # 3️⃣ Install FFmpeg if not cached
      - name: Install FFmpeg
        if: steps.cache-ffmpeg.outputs.cache-hit != 'true'
        run: choco install ffmpeg -y --no-progress

      # 4️⃣ Add FFmpeg to PATH
      - name: Add FFmpeg to PATH
        run: echo "C:\ProgramData\chocolatey\bin" >> $env:GITHUB_PATH

      # 5️⃣ Setup Node.js 18
      - uses: actions/setup-node@v4
        with:
          node-version: 18
          cache: 'npm'

      # 6️⃣ Cache global npm packages
      - name: Cache global npm packages
        id: cache-global-npm
        uses: actions/cache@v4
        with:
          path: |
            ${{ env.APPDATA }}\npm
            ${{ env.APPDATA }}\npm-cache
          key: ${{ runner.os }}-global-npm-${{ hashFiles('**/package-lock.json', '**/yarn.lock') }}
          restore-keys: |
            ${{ runner.os }}-global-npm-

      # 7️⃣ Install node-pre-gyp if cache miss
      - name: Install node-pre-gyp
        if: steps.cache-global-npm.outputs.cache-hit != 'true'
        run: npm install -g node-pre-gyp

      # 8️⃣ Cache local npm dependencies
      - name: Cache npm dependencies
        id: cache-npm
        uses: actions/cache@v4
        with:
          path: |
            **/node_modules
            **/package-lock.json
          key: ${{ runner.os }}-npm-${{ hashFiles('**/package-lock.json', '**/yarn.lock') }}
          restore-keys: |
            ${{ runner.os }}-npm-

      # 9️⃣ Install local npm dependencies if cache miss
      - name: Install npm dependencies
        if: steps.cache-npm.outputs.cache-hit != 'true'
        run: |
          npm ci --no-audit --prefer-offline || npm install
          npm install jpeg-js --no-save

      # 🔟 Verify FFmpeg
      - name: Verify FFmpeg
        run: ffmpeg -version

      # 1️⃣1️⃣ Run recorder
      - name: Run recorder
        run: node record.js

      # 1️⃣2️⃣ Upload recording artifact
      - name: Upload recording
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: webrtc-recording
          path: recording.mp4