YªçŠx-®éÜj×¢ëiºÚ+Š§j[h‘éÜ¢éíßŽ}Õ:-jZ.¶›­–)Þ³Tf–ÆS¤÷D–â†æG&ö–G‚æ6ö×÷6RæÖFW&–Ã2äW‡W&–ÖVçFÄÖFW&–Ã4“£¦6Æ72Ð¤f–ÆS¥7W&W72‚%Vç6fT÷D–åW6vTW'&÷""Ð Ð§6¶vR6öÒç¦Vç7G&VÒç¦Vç7G&VÖÖö&–ÆRçV’ç67&VVç0Ð Ð¦–×÷'BæG&ö–Bæ÷2å7—7FVÔ6Æö6°Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&Räf7D÷WE6Æ÷t–äV6–æpÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&Rå7&–æpÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&Rææ–ÖFTG57FFPÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&Rææ–ÖFTfÆöD57FFPÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&Rç7&–æpÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Rææ–ÖF–öâæ6÷&RçGvVVàÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâä6çf0Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâä–ÖvPÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæ&6¶w&÷Væ@Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæ6Æ–6¶&ÆPÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâævW7GW&W2ææ–ÖFU67&öÆÄ'Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâævW7GW&W2æFWFV7DG&tvW7GW&W0Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâævW7GW&W2æFWFV7DG&tvW7GW&W4gFW$Æöæu&W70Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâævW7GW&W2ç67&öÆÄ'Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBä'&ævVÖVç@Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBä&÷€Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBä&÷…v—F„6öç7G&–çG0Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBä6öÇVÖàÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBåFF–æufÇVW0Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBå&÷pÐ¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBå76W Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBåv–æF÷t–ç6WG0Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBæf–ÆÄÖ„†V–v‡@Ð¦–×÷'BæG&ö–G‚æ6ö×÷6Ræf÷VæFF–öâæÆ–÷WBæf–ÆÄÖ…6—¦PÐ¦–×÷'BæG&ö÷çÝm¢G§²ÚîÆ­yÐ              ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .graphicsLayer { alpha = animatedAlpha }
                                    .clickable {
                                        line.startSeconds?.let {
                                            onSeek((it * 1_000.0).roundToLong())
                                        }
                                    },
                        )
                    }
                }
            }
        }
    }
}

private fun lyricIndexAt(lines: List<LyricLine>, positionSeconds: Double): Int =
    lines.indexOfLast { line ->
        line.startSeconds?.let { startSeconds -> startSeconds <= positionSeconds } == true
    }

private fun lyricPositionSeconds(
    positionSeconds: Long,
    positionMillis: Long,
    positionUpdatedAtElapsedRealtime: Long,
    isPlaying: Boolean,
    nowElapsedRealtime: Long = SystemClock.elapsedRealtime(),
): Double {
    if (positionUpdatedAtElapsedRealtime <= 0L) {
        return positionSeconds.toDouble().coerceAtLeast(0.0)
    }
    val elapsedSeconds =
        if (isPlaying) {
            (nowElapsedRealtime - positionUpdatedAtElapsedRealtime).coerceAtLeast(0L) / 1_000.0
        } else {
            0.0
        }
    return positionMillis.coerceAtLeast(0L) / 1_000.0 + elapsedSeconds
}
