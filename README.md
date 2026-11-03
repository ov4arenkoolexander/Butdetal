# BUTDETAL 2.0.14

Український Android-довідник з будівництва. Вихідний Android-проєкт зберігається безпосередньо в цьому репозиторії; окремий `source.zip` для збірки не потрібен.

## Збірка APK

1. Відкрий **Actions → Build and publish BUTDETAL APK**.
2. Для debug APK запусти workflow вручну або зроби push у `main`.
3. Після успішної збірки завантаж debug APK з **Artifacts → BUTDETAL-debug**.
4. Релізний APK публікується лише для version tag формату `vX.Y.Z`, який має збігатися з `versionName` у `app/build.gradle`.

## Безпека Supabase

У застосунку використовуйте лише publishable/anon ключ. Ніколи не додавайте service_role або інші секретні ключі до Android-коду чи репозиторію.

## Перевірка

Workflow виконує статичну перевірку та Gradle build. Це не замінює перевірку OCR, синхронізації й навігації на реальному пристрої.
