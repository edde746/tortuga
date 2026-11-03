package com.edde746.tortuga.data

import androidx.test.platform.app.InstrumentationRegistry

internal actual fun inMemoryDatabase(): DatabaseFactory =
    DatabaseFactory.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
