package com.bleural.croissant.ui.beam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoundEffect
import androidx.compose.ui.platform.SoundEffect
import com.bleural.croissant.Core
import com.bleural.croissant.UiShell
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.CroissantTheme
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Place
import com.bleural.croissant.ui.RoomState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.ShutterKey
import com.bleural.croissant.ui.Wheel

/**
 * Beam as a [UiShell]: the stock rabbitOS look, a pure black ground, white lowercase
 * words and one neon per feature. Its structure: Home, the menu of one panel per feature, and
 * the features opened from it. One variant, `night` (ui/Designs.kt).
 *
 * It owns the back stack and the room's springs, because the side button writes to both
 * from outside the composition.
 */
class BeamUi(val variant: String) : UiShell {

    private val nav = NavState()
    /**
     * Home, the panels and the open feature: the same springs as every design's room
     * (ui/Motion.kt). Its places are laid out again on Home (Shell.kt), and leaving Home lands
     * on Ask, under the recents (2026-10-17).
     */
    private val room = RoomState(PLACES, leavesToEntry = true)

    /** Home's day and its croissant (HomeDay.kt), here because a wake resets them. */
    private val home = HomeState()

    /** Where a lock left off (2026-11-18), for the swipe up from Home to go back to. */
    private val lock = LockFace(nav, room)

    @Composable
    override fun Content(core: Core) {
        CompositionLocalProvider(LocalSoundEffect provides Silent) {
            // BeamType leaves most line heights unset, so they come from the theme's text style.
            CroissantTheme { BeamShell(core, nav, room, home, lock) }
        }
    }

    override fun cutToAsk() {
        nav.home()
        room.cutToAsk()
    }

    override fun cutToAgentChat(agent: String, session: String?, cwd: String) {
        nav.home()
        nav.push(if (session != null) Screen.AgentChat(agent, session, cwd) else Screen.AgentNew(agent, cwd), sound = null)
    }

    override fun toggleCamera() {
        if (room.openTarget > 0.5f && room.last == Feature.Camera) {
            room.closePanel()
            Cues.play(Sound.ScreenOut)
        } else {
            nav.home()
            room.goTo(room.placeOf(Feature.Camera))
            room.leaveHome()
            room.openPanel()
            Cues.play(Sound.ScreenIn)
        }
    }

    /** Only an open feature can be the camera's live preview, and only that registers a shutter. */
    override fun shutterPress(): Boolean = room.openTarget > 0.5f && ShutterKey.press()

    override fun wheel(delta: Int) = Wheel.tick(delta)

    override fun openEsimAdd() {
        if (nav.current is Screen.EsimAdd) nav.replace(Screen.EsimAdd()) else nav.push(Screen.EsimAdd())
    }

    /** The first start of a new version: its page, saying so, over Home. */
    override fun openUpdate() {
        if (nav.current != Screen.Update) nav.push(Screen.Update)
    }

    /** A start after a restore: the backup page, saying what it came to, over Home. */
    override fun openBackup() {
        if (nav.current != Screen.Backup) nav.push(Screen.Backup)
    }

    /** Ask called one of the app's tools: its page over whatever is up, unless it is already. */
    override fun openAtelierApp(id: String) {
        if ((nav.current as? Screen.AtelierApp)?.id != id) nav.push(Screen.AtelierApp(id))
    }

    override fun settle() = room.snap()

    override fun locked() = lock.lock()

    /** A wake puts Home's day away. */
    override fun woke() = home.snapShut()
}

/**
 * The lock (2026-11-18), as a phone's: the side button's short press keeps where it was and
 * leaves Home up, with its croissant, so waking shows Home; the swipe up from the bottom there
 * goes back ([unlock]). Anything else done from Home forgets it (Shell.kt). A screen that timed
 * out is not locked: waking shows where it was.
 */
internal class LockFace(private val nav: NavState, private val room: RoomState) {
    private class Left(val place: Place?, val open: Boolean, val pages: List<Screen>)

    private var left: Left? = null

    val held: Boolean get() = left != null

    fun lock() {
        val onHome = room.homeTarget > 0.5f
        val pages = nav.stack.drop(1)
        if (!onHome || pages.isNotEmpty()) {
            // Locked again from Home, nothing changes, so the first lock's place is kept.
            left = Left(if (onHome) null else room.lastPlace, room.openTarget > 0.5f, pages)
            nav.home()
            room.goHome()
            room.snap()
        }
    }

    fun forget() { left = null }

    /** Back to where the lock left off: true when there was somewhere. */
    fun unlock(): Boolean {
        val l = left ?: return false
        left = null
        if (l.place != null) {
            val i = room.places.indexOf(l.place).takeIf { it >= 0 } ?: room.placeOf(l.place.feature)
            if (i >= 0) {
                room.goTo(i)
                room.leaveHome()
                if (l.open) room.openPanel()
            }
        } else room.goHome()
        l.pages.forEach { nav.push(it, sound = null) }
        Cues.play(Sound.ScreenIn)
        return true
    }
}

/** Taps sound through what they do, never as the system click. */
private val Silent = object : SoundEffect {
    override fun playClickSound() {}
}
