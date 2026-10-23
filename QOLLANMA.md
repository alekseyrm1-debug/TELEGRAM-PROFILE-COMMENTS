# Qo'llanma: kodni ishga tushirish va Durov qoidasiga mos topshirish

Durov talabi: **ishlaydigan, ilovaga qo'shishga tayyor kod** va **shu kod bilan ishlayotgan funksiyaning demo videosi**.
Bu papkada ikkala platforma uchun kod bor. Video esa haqiqiy ilovadan, ya'ni shu koddan yig'ilgan Telegramdan olinishi kerak.

## 1. Android APK'ni olish (Windows'da hech narsa o'rnatmasdan)

Eng oson yo'l GitHub Actions. Ilova GitHub serverlarida yig'iladi, siz tayyor APK'ni yuklab olasiz.

1. github.com'da akkaunt oching (bo'lmasa).
2. https://github.com/DrKLO/Telegram sahifasida **Fork** tugmasini bosing.
3. Patchni fork'ingizga qo'shing. Ikki yo'l bor:
   * **Oson yo'l:** fork nomini menga yozing (masalan `sizning-login/Telegram`). Men `profile-comments` branch'ini yaratib, kodni o'zim push qilaman.
   * **Qo'lda:** Windows'ga [Git](https://git-scm.com/download/win) o'rnating va quyidagilarni bajaring:
     ```
     git clone https://github.com/SIZNING-LOGIN/Telegram.git
     cd Telegram
     git checkout -b profile-comments
     git apply ..\profile-comments-android.patch
     git add -A
     git commit -m "Profile Comments"
     git push -u origin profile-comments
     ```
4. Fork'ingizda **Actions** bo'limini oching va workflow'larni yoqing. Keyin **Build Profile Comments APK → Run workflow** (branch: `profile-comments`) ni bosing.
5. Yig'ish 1–2 soat davom etadi. Tugagach, o'sha sahifadagi **Artifacts** bo'limidan `telegram-profile-comments-apk` ni yuklab oling.
6. ZIP ichidagi `app.apk` ni telefonga o'rnating. U **Telegram Beta** nomi bilan, oddiy Telegram'ingiz yonida alohida o'rnatiladi.

> Android Studio orqali Windows'da yig'ish ham mumkin, lekin Telegram'ning native qismi (NDK) Windows'da ko'p muammo beradi. GitHub Actions ishonchliroq.

## 2. Videoni yozish (Android)

Telefonning o'z ekran yozuvchisini yoqing va avvalgi videolardagi tartibga amal qiling.

**Egasi uchun (For owners)**
1. Telegram Beta'da o'z profilingizni oching → **Comments** tabi. Demo izohlar avtomatik qo'shiladi: Noah, Emma, Liam, Sofia, kutayotgan Olivia va Alex.
2. "Waiting for approval" bo'limida **Approve** (Alex) va **Dismiss** (Olivia) tugmalarini bosing.
3. Izohni bosing → **Reply** → javob yozing → **Post reply**.
4. Izohni bosing → **Pin to top**, keyin boshqasiga **Hide from visitors**.
5. Filtr chiplari: **👍 Recommend** → **All**.
6. Izohga ikki marta bosing (❤️ qo'shiladi).
7. Ro'yxat sarlavhasidagi **Settings** → yoki *Settings → Privacy and Security → Profile Comments*: **My Contacts**, **Approve new comments**.

**Mehmon uchun (For visitors)**
1. Kontaktlaringizdan biri profilini oching → **Comments** tabi.
2. Pastdagi **Leave a comment** → 2–3 ta teg → matn → **Post comment**.
3. Tugma endi "You can comment again in 23 h 59 min" deb ko'rsatadi (24 soatlik limit).
4. Izohni bosing → **Message …** shaxsiy chatni ochadi.

Yozib olingan videoni avvalgi videolar kabi gradient fonga joylashtirsangiz yaxshi ko'rinadi, lekin bu majburiy emas. Asosiysi, video haqiqiy ilovadan bo'lishi.

## 3. iOS

iOS kodi `ios/` papkasida tayyor, lekin uni yig'ish uchun **Mac va Xcode** kerak. Windows'da yig'ib bo'lmaydi. Variantlar:
* Mac'i bor tanishdan yordam so'rash: `git apply profile-comments-ios.patch`, keyin Telegram-iOS README'dagi qadamlar.
* Soatbay ijaraga olinadigan bulutli Mac (masalan MacinCloud).
* Hozircha Android video + ikkala platforma kodi bilan topshirish. Telegram'ga bitta platformadagi video ham yetadi, iOS kodi esa "tayyor implementatsiya" sifatida ilova qilinadi.

## 4. @designers'ga nima yuborasiz

1. Kod: fork'ingizdagi `profile-comments` branch'iga havola. iOS uchun ham Telegram-iOS fork qilib, xuddi shunday branch ochish mumkin. Yoki shu papkadagi patch fayllarni yuboring.
2. Haqiqiy ilovadan yozilgan demo video (Android).
3. Qisqa matn (`posts.md` dagi matnni ishlating, "live prototype" o'rniga kod havolasini qo'ying).

Veb-prototip (`prototype/profil-devori.html`) va avvalgi render videolarni **asosiy demo sifatida yubormang**. Ular ilova kodi emas, Telegram buni "kod orqasida turmagan video" deb hisoblashi mumkin. Ularni faqat qo'shimcha dizayn materiali sifatida ilova qilish mumkin.
