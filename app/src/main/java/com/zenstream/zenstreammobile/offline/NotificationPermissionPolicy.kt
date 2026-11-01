package com.zenstream.zenstreammobile.offline

internal fun shouldIntroduceNotificationPermission(
    requestedOnStartup: Boolean,
    api: Int,
    granted: Boolean,
    introductionShown: Boolean,
): Boolean = requestedOnStartup && api >= 33 && !granted && !introductionShown

internal fun canRequestNotificationPermission(
    api: Int,
    granted: Boolean,
    previouslyRequested: Boolean,
    shouldShowRationale: Boolean,
): Boolean = api >= 33 && !granted && (!previouslyRequested || shouldShowRationale)
