package app.gamenative.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Raw color primitives for the Pluvia app.
 * These are the base colors used to construct theme palettes.
 */

// Brand
val PluviaPrimary = Color(0xFFA21CAF)
val PluviaSeed = Color(0x284561FF)

// Backgrounds
val PluviaBackground = Color(0xFF09090B)
val PluviaSurface = Color(0xFF12121A)
val PluviaSurfaceElevated = Color(0xFF1A1A24)
val PluviaCard = Color(0xFF09090B)

// Foregrounds
val PluviaForeground = Color(0xFFFAFAFA)
val PluviaForegroundMuted = Color(0xFF94969C)

// Secondary
val PluviaSecondary = Color(0xFF27272A)

// Accents
val PluviaCyan = Color(0xFF00D4FF)
val PluviaPurple = Color(0xFF8B5CF6)
val PluviaPink = Color(0xFFEC4899)
val IicTeal = Color(0xFF14B8A6)
val IicViolet = Color(0xFF7C3AED)

// NovaGN brand — premium/minimal: a single electric-indigo accent on near-black.
// This is THE brand color; the old teal→violet and cyan→violet→pink gradients are
// retired in favour of one confident accent (with a subtle 2-stop variant for
// large surfaces that genuinely need depth).
val NovaAccent = Color(0xFF6D5BF6) // primary accent (indigo-violet)
val NovaAccentBright = Color(0xFF8B7BFF) // hover / focus / highlight tint
val NovaAccentDeep = Color(0xFF4B3CD0) // pressed / gradient end-stop
val NovaInk = Color(0xFF0B0B0F) // brand near-black (matches the emblem/banner)

// Semantic
val PluviaSuccess = Color(0xFF10B981)
val PluviaWarning = Color(0xFFF59E0B)
val PluviaDanger = Color(0xFFEF4444)
val PluviaDestructive = Color(0xFF7F1D1D)

// Border
val PluviaBorder = Color(0xFF3A3A4A)

// Status - Installed/Download states
val StatusInstalled = Color(0xFF4CAF50)
val StatusDownloading = Color(0xFF00BCD4)
val StatusAvailable = Color(0xFF2196F3)
val StatusAway = Color(0xFFFF9800)
val StatusOffline = Color(0xFF9E9E9E)

// Friend states
val FriendOnline = Color(0xFF6DCFF6)
val FriendOffline = Color(0xFF7A7A7A)
val FriendInGame = Color(0xFF90BA3C)
val FriendAwayOrSnooze = Color(0x806DCFF6)
val FriendInGameAwayOrSnooze = Color(0x8090BA3C)
val FriendBlocked = Color(0xFF983D3D)

// Compatibility
val CompatibilityGood = Color(0xFF4CAF50)
val CompatibilityGoodBg = Color(0xFF1B5E20)
val CompatibilityPartial = Color(0xFF8BC34A)
val CompatibilityPartialBg = Color(0xFF33691E)
val CompatibilityUnknown = Color(0xFF9E9E9E)
val CompatibilityUnknownBg = Color(0xFF424242)
val CompatibilityBad = Color(0xFFEF5350)
val CompatibilityBadBg = Color(0xFFB71C1C)
