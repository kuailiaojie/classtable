package com.kxin.classtable.data

import com.kxin.classtable.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 实时天气:只留展示需要的字段,缺项一律为 null,由界面决定画不画。 */
data class Weather(
    /** 定位到的最细一级:区县优先,其次城市(如「荆州区」/「北京」)。 */
    val place: String,
    /** 天气现象文案(晴 / 多云 / 阵雨……)。 */
    val text: String,
    /** 天气图标代码(weather_icon),文案认不出时用它归类晴雨。 */
    val icon: String,
    val temperature: Double,
    /** 体感温度(extended)。 */
    val feelsLike: Double?,
    /** 今日最高 / 最低温(forecast)。 */
    val tempMax: Double?,
    val tempMin: Double?,
    val humidity: Int?,
    val windDirection: String,
    val windPower: String,
    /** AQI 与等级描述(extended)。 */
    val aqi: Int?,
    val aqiCategory: String?,
    /** 紫外线指数(extended)。 */
    val uv: Double?,
)

/**
 * uapis.cn 天气接口(GET /api/v1/misc/weather)。
 *
 * 不传 city / adcode 时接口按**请求来源 IP** 自动定位 —— 手机直连,看到的就是设备出口
 * IP 所在的城市,所以不需要申请系统定位权限(代价是精度只到城市/区县,够用)。
 *
 * `extended`(体感 / 湿度 / 紫外线 / AQI)与 `forecast`(今日最高最低)在访客额度下也一并
 * 返回,所以只有 key 失效时才退回访客额度。
 */
object WeatherClient {

    private const val ENDPOINT = "https://uapis.cn/api/v1/misc/weather"

    /** key 被判定失效后置位,之后直接按访客额度走,不再每个刷新周期白跑一次 400。 */
    @Volatile
    private var keyDisabled = false

    suspend fun current(): Result<Weather> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$ENDPOINT?extended=true&forecast=true"
            val body = try {
                request(url, BuildConfig.WEATHER_API_KEY.takeIf { it.isNotBlank() && !keyDisabled })
            } catch (error: WeatherHttpException) {
                // key 无效 / 额度用尽:接口提示「删除 Authorization 请求头可使用访客额度」。
                // 访客额度返回的数据与 Pro 一致,悄悄降级即可,不影响界面。
                if (error.status != 400) throw error
                keyDisabled = true
                request(url, null)
            }
            parse(JSONObject(body))
        }
    }

    private fun request(url: String, apiKey: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (apiKey != null) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw WeatherHttpException(status, message?.takeIf { it.isNotBlank() } ?: "天气接口返回 $status")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: JSONObject): Weather {
        val weather = json.optString("weather").trim()
        check(weather.isNotEmpty()) { "天气数据为空" }
        return Weather(
            place = json.optString("district").trim().ifBlank { json.optString("city").trim() },
            text = weather,
            icon = json.optString("weather_icon").trim(),
            temperature = json.optDouble("temperature", 0.0),
            feelsLike = json.doubleOrNull("feels_like"),
            tempMax = json.doubleOrNull("temp_max"),
            tempMin = json.doubleOrNull("temp_min"),
            humidity = json.intOrNull("humidity"),
            windDirection = json.optString("wind_direction").trim(),
            windPower = json.optString("wind_power").trim(),
            aqi = json.intOrNull("aqi"),
            aqiCategory = json.optString("aqi_category").trim().ifBlank { null },
            uv = json.doubleOrNull("uv"),
        )
    }

    /** `optDouble` 缺键时返回 NaN,这里统一转成 null。 */
    private fun JSONObject.doubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

    private fun JSONObject.intOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private class WeatherHttpException(val status: Int, message: String) : IOException(message)
}
