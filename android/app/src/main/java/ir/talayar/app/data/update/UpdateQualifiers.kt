package ir.talayar.app.data.update

import javax.inject.Qualifier

/**
 * Hilt qualifiers for the update channel's HTTP clients.
 *
 * The update flow needs *different* timeouts from the price gateway:
 *  - [UpdateApi] fetches a few kilobytes of release metadata, so it stays tightly
 *    bounded (a filtered host must not hang the «بررسی بروزرسانی» button);
 *  - [UpdateDownloads] streams a multi-megabyte APK, so it must not have an overall
 *    call timeout at all — the previous single 25 s `callTimeout` cancelled large
 *    downloads mid-stream on slow mobile links.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateApi

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateDownloads
