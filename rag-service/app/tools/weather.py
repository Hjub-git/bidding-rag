"""function call 工具注册表。

当前提供天气查询工具（mock 数据），后续可接入真实天气 API，
或通过 MCP 接入更多外部工具。
"""


def _tool_get_weather(city):
    """查询城市天气（mock 数据，后续可接真实天气 API）"""
    weather_data = {
        "北京": "晴，25℃",
        "上海": "多云，30℃",
        "广州": "阵雨，28℃",
        "深圳": "晴，29℃",
    }
    return weather_data.get(city, f"{city} 的天气暂未收录，请换一个城市试试")


# 工具名 → 工具函数 的映射，function call 时按名调用
TOOLS = {
    "get_weather": _tool_get_weather,
}
