package com.portalmuse.core

import java.util.Locale

/**
 * The language of what people read, hear or send to their Muse: French on a French device, English
 * everywhere else. Texts for the protocol, the logs and the Muse's commands are always in English.
 */
fun tr(english: String, french: String): String = if (Locale.getDefault().language == "fr") french else english
