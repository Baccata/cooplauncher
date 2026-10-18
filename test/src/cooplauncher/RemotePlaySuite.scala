package cooplauncher

class RemotePlaySuite extends munit.FunSuite:
  test("parseResponse detects the Remote Play Together category") {
    val body = """{"289070":{"success":true,"data":{"categories":[
        {"id":1,"description":"Multi-player"},
        {"id":44,"description":"Remote Play Together"}]}}}"""
    assertEquals(RemotePlay.parseResponse("289070", body), Some(true))
  }

  test("parseResponse returns false when the category is absent") {
    val body = """{"632360":{"success":true,"data":{"categories":[
        {"id":9,"description":"Co-op"},
        {"id":38,"description":"Online Co-op"}]}}}"""
    assertEquals(RemotePlay.parseResponse("632360", body), Some(false))
  }

  test("parseResponse matches the category name, not just the id") {
    val body = """{"1":{"success":true,"data":{"categories":[
        {"id":99,"description":"Remote Play Together"}]}}}"""
    assertEquals(RemotePlay.parseResponse("1", body), Some(true))
  }

  test("parseResponse treats failures and malformed payloads as unknown") {
    assertEquals(RemotePlay.parseResponse("620", """{"620":{"success":false}}"""), None)
    assertEquals(RemotePlay.parseResponse("620", "{}"), None)
    assertEquals(RemotePlay.parseResponse("620", "not json"), None)
    assertEquals(RemotePlay.parseResponse("620", ""), None)
  }
