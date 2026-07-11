package com.looker.droidify.compose.appList

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BrowserUpdated
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Castle
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.CrueltyFree
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FiberSmartRecord
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalPlay
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Money
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PermPhoneMsg
import androidx.compose.material.icons.filled.PhotoSizeSelectActual
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.StackedLineChart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VideoChat
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.VoiceChat
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A representative icon for each catalogue category. Mirrors F-Droid's category→icon mapping so the
 * (fine-grained) F-Droid/IzzyOnDroid categories are all covered; anything unknown gets a neutral tag.
 */
fun categoryIcon(category: String): ImageVector = when (category) {
    "AI Chat" -> Icons.Filled.VoiceChat
    "App Manager" -> Icons.Filled.Apps
    "App Store & Updater" -> Icons.Filled.Storefront
    "Action Game" -> Icons.Filled.SportsMartialArts
    "Battery" -> Icons.Filled.BatteryChargingFull
    "Board Game" -> Icons.Filled.DeveloperBoard
    "Bookmark" -> Icons.Filled.Bookmarks
    "Browser" -> Icons.Filled.OpenInBrowser
    "Calculator" -> Icons.Filled.Calculate
    "Calendar & Agenda" -> Icons.Filled.CalendarMonth
    "Camera" -> Icons.Filled.CameraAlt
    "Card Game" -> Icons.Filled.Style
    "Casual Game" -> Icons.Filled.Gamepad
    "Clock" -> Icons.Filled.AccessTime
    "Cloud Storage & File Sync" -> Icons.Filled.Cloud
    "Connectivity" -> Icons.Filled.SignalCellularAlt
    "Contact" -> Icons.Filled.Contacts
    "Development" -> Icons.Filled.DeveloperMode
    "Dice" -> Icons.Filled.Casino
    "Diet" -> Icons.Filled.Fastfood
    "DNS & Hosts" -> Icons.Filled.Dns
    "Download" -> Icons.Filled.Download
    "Draw" -> Icons.Filled.Draw
    "Ebook Reader" -> Icons.AutoMirrored.Filled.MenuBook
    "Educational Game" -> Icons.Filled.School
    "Email" -> Icons.Filled.AlternateEmail
    "Emulator" -> Icons.Filled.VideogameAsset
    "File Encryption & Vault" -> Icons.Filled.EnhancedEncryption
    "File Manager" -> Icons.Filled.FileCopy
    "File Transfer" -> Icons.Filled.UploadFile
    "Finance Manager" -> Icons.Filled.MonetizationOn
    "Firewall" -> Icons.Filled.AppBlocking
    "Flashlight" -> Icons.Filled.FlashlightOn
    "Forum" -> Icons.Filled.Image
    "Gallery" -> Icons.Filled.PhotoSizeSelectActual
    "Game Helper" -> Icons.Filled.Handyman
    "Graphics" -> Icons.Filled.Brush
    "Habit Tracker" -> Icons.Filled.TrackChanges
    "Health Manager" -> Icons.Filled.HealthAndSafety
    "Icon Pack" -> Icons.Filled.Collections
    "Internet" -> Icons.Filled.Language
    "Inventory" -> Icons.Filled.AllInbox
    "Keyboard & IME" -> Icons.Filled.Keyboard
    "Launcher" -> Icons.Filled.Home
    "Local Media Player" -> Icons.Filled.LocalPlay
    "Location Tracker & Sharer" -> Icons.Filled.MyLocation
    "Lyrics" -> Icons.AutoMirrored.Filled.QueueMusic
    "Market & Price" -> Icons.Filled.StackedLineChart
    "Meditation" -> Icons.Filled.SelfImprovement
    "Messaging" -> Icons.AutoMirrored.Filled.Message
    "Money" -> Icons.Filled.Money
    "Multimedia" -> Icons.Filled.MusicVideo
    "Music Practice Tool" -> Icons.Filled.MusicNote
    "Navigation" -> Icons.Filled.Navigation
    "Network Analyzer" -> Icons.Filled.NetworkCheck
    "News" -> Icons.Filled.Newspaper
    "Note" -> Icons.Filled.NoteAlt
    "Online Media Player" -> Icons.Filled.Airplay
    "Party Game" -> Icons.Filled.Celebration
    "Pass Wallet" -> Icons.Filled.AccountBalanceWallet
    "Password & 2FA" -> Icons.Filled.Password
    "Phone & SMS" -> Icons.Filled.PermPhoneMsg
    "Platformer Game" -> Icons.Filled.CrueltyFree
    "Podcast" -> Icons.Filled.Podcasts
    "Public Transport" -> Icons.Filled.DirectionsBus
    "Puzzle Game" -> Icons.Filled.Extension
    "Radio" -> Icons.Filled.Radio
    "Reading" -> Icons.AutoMirrored.Filled.MenuBook
    "Recipe Manager" -> Icons.Filled.RestaurantMenu
    "Recorder" -> Icons.Filled.FiberSmartRecord
    "Religion" -> Icons.Filled.Church
    "Role-Playing Game" -> Icons.Filled.Diversity3
    "Remote Access" -> Icons.Filled.BrowserUpdated
    "Remote Controller" -> Icons.Filled.SettingsRemote
    "Schedule" -> Icons.Filled.CalendarMonth
    "Science & Education" -> Icons.Filled.Science
    "Security" -> Icons.Filled.Security
    "Shooter Game" -> Icons.Filled.CenterFocusWeak
    "Strategy Game" -> Icons.Filled.Castle
    "Shopping List" -> Icons.Filled.ShoppingCart
    "Social Network" -> Icons.Filled.Groups
    "Sport Game" -> Icons.Filled.SportsSoccer
    "Sports & Health" -> Icons.Filled.HealthAndSafety
    "System" -> Icons.Filled.Settings
    "Task" -> Icons.Filled.TaskAlt
    "Text Editor" -> Icons.Filled.EditNote
    "Text to Speech" -> Icons.Filled.RecordVoiceOver
    "Theming" -> Icons.Filled.Style
    "Time" -> Icons.Filled.AccessTime
    "Time Tracker" -> Icons.Filled.Timelapse
    "Timer" -> Icons.Filled.Timer
    "Translation & Dictionary" -> Icons.Filled.Translate
    "Visual Novel" -> Icons.Filled.ModeComment
    "Voice & Video Chat" -> Icons.Filled.VideoChat
    "Unit Convertor" -> Icons.Filled.CurrencyExchange
    "VPN & Proxy" -> Icons.Filled.VpnLock
    "Wallet" -> Icons.Filled.Wallet
    "Wallpaper" -> Icons.Filled.Wallpaper
    "Weather" -> Icons.Filled.WbSunny
    "Word Game" -> Icons.Filled.Sos
    "Workout" -> Icons.Filled.FitnessCenter
    "Writing" -> Icons.Filled.EditNote
    else -> Icons.Filled.Category
}
