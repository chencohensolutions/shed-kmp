package com.chencohensolution.shed.journal

import com.chencohensolution.shed.BackendException
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The server answered a GraphQL request with errors; [messages] are theirs. */
class GraphQLException(val messages: List<String>) : Exception(messages.joinToString("; "))

/**
 * Sends one GraphQL operation to [origin]'s `/query` and returns its `data`. Shed asks a handful of fields, so it writes
 * them by hand rather than generating code from the schema (journal-server-go graph/schema). Network failures become
 * [BackendException] with offline set, a refused certificate one with unauthorized set.
 */
internal suspend fun HttpClient.graphql(origin: String, query: String, variables: Map<String, JsonElement> = emptyMap()): JsonObject {
    val body = buildJsonObject {
        put("query", query)
        put("variables", JsonObject(variables))
    }
    val response = try {
        post("$origin/query") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BackendException("The server didn't answer: ${e.message ?: e::class.simpleName}", offline = true, cause = e)
    }
    if (response.status.value == 401) throw BackendException("The server refused this device", unauthorized = true)
    if (!response.status.isSuccess()) throw BackendException("The server answered ${response.status.value}", offline = response.status.value >= 500)
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    json["errors"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { errors ->
        throw GraphQLException(errors.map { it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: it.toString() })
    }
    return json["data"]?.jsonObject ?: throw GraphQLException(listOf("No data"))
}

internal fun JsonObject.obj(name: String): JsonObject = this[name]?.jsonObject ?: error("No $name in $this")

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(name: String): Long? = str(name)?.toLongOrNull()
