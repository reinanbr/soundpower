// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.setup

import com.reinanbr.soundpower.effect.EffectController.Connection
import com.reinanbr.soundpower.protocol.ProtocolInfo
import com.reinanbr.soundpower.root.MagiskInfo
import com.reinanbr.soundpower.root.ModuleProp
import com.reinanbr.soundpower.root.ModuleState
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupStepTest {
    private val bundled = ModuleProp("v0.2.0", 200)
    private val magisk = MagiskInfo("31.1", 31100)
    private val active = ModuleState(installed = ModuleProp("v0.2.0", 200))
    private val connected = Connection.Connected(ProtocolInfo(1, 0, 0x200, 32, 3))
    private val ready = SetupReport(root = true, magisk = magisk, module = active, registered = true, connection = connected)

    private fun step(r: SetupReport) = r.step(bundled)

    @Test
    fun walksThroughTheSetup() {
        assertEquals(SetupStep.CHECKING, step(SetupReport()))
        assertEquals(SetupStep.NO_ROOT, step(SetupReport(root = false)))
        assertEquals(SetupStep.NO_MAGISK, step(SetupReport(root = true)))
        assertEquals(SetupStep.NO_MAGISK, step(SetupReport(root = true, magisk = MagiskInfo("23.0", 23000))))
        assertEquals(SetupStep.CHECKING, step(SetupReport(root = true, magisk = magisk)))
        assertEquals(SetupStep.INSTALL, step(ready.copy(module = ModuleState(), connection = null)))
        assertEquals(SetupStep.UPDATE, step(ready.copy(module = ModuleState(installed = ModuleProp("v0.1.0", 100)))))
        assertEquals(SetupStep.REBOOT, step(ready.copy(module = active.copy(updateFlag = true))))
        assertEquals(SetupStep.DISABLED, step(ready.copy(module = active.copy(disabled = true))))
        assertEquals(SetupStep.NOT_LOADED, step(ready.copy(registered = false, connection = null)))
        assertEquals(SetupStep.NOT_LOADED, step(ready.copy(connection = Connection.Failed("init failed"))))
        assertEquals(SetupStep.INCOMPATIBLE, step(ready.copy(connection = Connection.Incompatible(ProtocolInfo(2, 0, 0, 32, 3)))))
        assertEquals(SetupStep.READY, step(ready))
    }

    @Test
    fun aRemovedModuleIsInstalledAgainAndANewerOneIsKept() {
        assertEquals(SetupStep.INSTALL, step(ready.copy(module = active.copy(removing = true))))
        assertEquals(SetupStep.READY, step(ready.copy(module = ModuleState(installed = ModuleProp("v0.3.0", 300)))))
    }

    @Test
    fun aLoadedEffectIsUsableWithoutRoot() {
        assertEquals(SetupStep.READY, step(SetupReport(root = false, registered = true, connection = connected)))
        assertEquals(SetupStep.NO_ROOT, step(SetupReport(root = false, registered = true, connection = Connection.Failed("x"))))
    }
}
