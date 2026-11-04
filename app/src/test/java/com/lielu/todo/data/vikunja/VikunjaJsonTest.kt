package com.lielu.todo.data.vikunja

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VikunjaJsonTest {
    @Test
    fun parseTokenLitLeToken() {
        assertEquals("tok123", VikunjaJson.parseToken(JSONObject().put("token", "tok123")))
    }

    @Test
    fun parseUserPrefereUsernameSinonEmail() {
        assertEquals("moi", VikunjaJson.parseUser(JSONObject().put("username", "moi").put("email", "m@x.eu")))
        assertEquals("m@x.eu", VikunjaJson.parseUser(JSONObject().put("email", "m@x.eu")))
        assertEquals("", VikunjaJson.parseUser(JSONObject()))
    }
}
