package com.jhon.agenticos.core

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jhon.agenticos.R

/** Quick Settings tile in :core: a tap moves Off -> Night shift -> Driving -> Off. The home chip re-reads mode.json
 *  when the home screen gets focus back, so it follows. */
class ModeTile : TileService() {
    override fun onStartListening() = draw(Modes.current())

    override fun onClick() {
        val m = Modes.next(Modes.current())
        try { Modes.set(m) } catch (_: Exception) { return }  // disk full: leave the tile showing what is really on
        draw(m)
    }

    private fun draw(m: Mode) {
        val t = qsTile ?: return
        t.label = "Mode"
        t.subtitle = m.label
        t.state = if (m == Mode.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        t.icon = Icon.createWithResource(this, when (m) { Mode.NIGHT -> R.drawable.ic_mode_night; Mode.DRIVING -> R.drawable.ic_mode_drive; else -> R.drawable.ic_mode_off })
        t.stateDescription = m.label
        t.updateTile()
    }
}
