package com.raman.photogate

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class PhotosApiTest {
    private val google = FakeServer()

    @Before
    fun setUp() {
        PhotosApi.base = google.url + "/v1"
    }

    @After
    fun tearDown() = google.close()

    @Test
    fun uploadSendsRawBytesAndReturnsToken() {
        google.on("/v1/uploads") { 200 to "UPLOAD-TOKEN\n" }
        val bytes = ByteArray(700_000) { (it % 251).toByte() } // bigger than one chunk
        val tok = PhotosApi.upload("at", bytes.inputStream(), "image/jpeg")
        assertEquals("UPLOAD-TOKEN", tok)
        val r = google.requests.single()
        assertEquals("POST", r.method)
        assertEquals("Bearer at", r.headers["authorization"])
        assertEquals("raw", r.headers["x-goog-upload-protocol"])
        assertEquals("image/jpeg", r.headers["x-goog-upload-content-type"])
        assertArrayEquals(bytes, r.body)
    }

    @Test(expected = PhotosApi.AuthExpired::class)
    fun expiredTokenIsRecognised() {
        google.on("/v1/uploads") { 401 to """{"error":{"code":401}}""" }
        PhotosApi.upload("old", ByteArray(3).inputStream(), "image/jpeg")
    }

    @Test
    fun createItemIntoAlbum() {
        google.on("/v1/mediaItems:batchCreate") {
            200 to """{"newMediaItemResults":[{"uploadToken":"u","status":{"message":"Success"},"mediaItem":{"id":"m"}}]}"""
        }
        PhotosApi.createItem("at", "u", "IMG_1.jpg", "alb1")
        val body = JSONObject(String(google.requests.single().body))
        assertEquals("alb1", body.getString("albumId"))
        val simple = body.getJSONArray("newMediaItems").getJSONObject(0).getJSONObject("simpleMediaItem")
        assertEquals("u", simple.getString("uploadToken"))
        assertEquals("IMG_1.jpg", simple.getString("fileName"))
    }

    @Test
    fun createItemWithoutAlbumOmitsIt() {
        google.on("/v1/mediaItems:batchCreate") { 200 to """{"newMediaItemResults":[{"status":{"message":"OK"}}]}""" }
        PhotosApi.createItem("at", "u", "a.jpg", null)
        assertFalse(JSONObject(String(google.requests.single().body)).has("albumId"))
    }

    @Test
    fun perItemFailureIsAnError() {
        google.on("/v1/mediaItems:batchCreate") {
            200 to """{"newMediaItemResults":[{"status":{"code":3,"message":"Failed: There was an error while trying to create this media item."}}]}"""
        }
        try {
            PhotosApi.createItem("at", "u", "a.jpg", null)
            fail()
        } catch (e: IOException) {
            assert(e.message!!.contains("Failed"))
        }
    }

    @Test
    fun deletedAlbumGivesHttpErrorWithCode() {
        google.on("/v1/mediaItems:batchCreate") { 400 to """{"error":{"code":400,"message":"Invalid album id"}}""" }
        try {
            PhotosApi.createItem("at", "u", "a.jpg", "gone")
            fail()
        } catch (e: PhotosApi.HttpError) {
            assertEquals(400, e.code)
        }
    }

    @Test
    fun createAlbumReturnsId() {
        google.on("/v1/albums") { 200 to """{"id":"ALB","title":"Trip"}""" }
        assertEquals("ALB", PhotosApi.createAlbum("at", "Trip"))
        assertEquals("Trip", JSONObject(String(google.requests.single().body)).getJSONObject("album").getString("title"))
    }
}
