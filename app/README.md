# TV Hub (Android + Windows + Linux)

مشروع واحد بكود مشترك:

- `core/` : الاتصال بواتساب (Baileys) وأوامر البوت واستقبال الأجزاء وخادم محلي. نفس الملفات على الأنظمة الثلاثة.
- `ui/index.html` : الواجهة والمشغّل (وسادة 3/5/10 دقائق، تشغيل متتابع بلا فجوات، إعادة الانتظار عند نفاد الوسادة).
- `main.js` : غلاف Electron لـ Windows وLinux.
- `serve.js` : بديل بلا Electron، يطبع رابطاً تفتحه في المتصفح.
- `android/nodejs-main.js` : نقطة دخول nodejs-mobile لأندرويد.

## الأوامر التي يرسلها التطبيق للبوت (لا تعديل على index.js)
- تشغيل: `.playm3u <رابط القناة> <المدة الكلية> <مدة الجزء>`
- إيقاف: `.stopplaym3u`

## التشغيل
    npm install
    npm start          # نافذة Electron
    npm run serve      # أو بالمتصفح
    npm run dist       # بناء محلي (exe على ويندوز، AppImage على لينكس)

## البناء على GitHub
ادمج المجلدين (`app/` و`.github/`) في جذر المستودع، ثم Actions ← Build TV Hub Desktop ← Run workflow.
الناتج في Artifacts: ملف `.exe` (ويندوز) و`.AppImage` (لينكس).
لينكس: `chmod +x TV-Hub-*.AppImage && ./TV-Hub-*.AppImage`

## أندرويد
1. انسخ `core/` و`ui/` و`package.json` (للاعتماديات) إلى `nodejs-project`، وسمِّ `android/nodejs-main.js` باسم `main.js`.
2. شغّل `npm install --omit=dev` داخل `nodejs-project`.
3. في النشاط الأصلي: شغّل محرك Node ثم اقرأ `server.json` من مجلد البيانات (أو استقبل رسالة `ready`) وحمّل `url` في WebView.
   - فعّل `javaScriptEnabled` و`domStorageEnabled` و`mediaPlaybackRequiresUserGesture=false`.
   - اسمح بـ HTTP لـ `127.0.0.1` (network security config).
   - لزر اختيار الملف: نفّذ `onShowFileChooser` في WebChromeClient، أو استخدم رابط m3u.

## أول استخدام
1. الإعدادات ← رقم البوت.
2. ربط حسابك: الرمز يظهر في التطبيق، أدخله في واتساب ← الأجهزة المرتبطة ← الربط برقم الهاتف.
3. أضف قائمة m3u (رابط أو ملف) بإنترنت عادي. تُحفظ القنوات والأيقونات محلياً.
4. اضغط قناة. أول جزء يصل بعد نحو دقيقة، ويبدأ العرض عند اكتمال الوسادة.
