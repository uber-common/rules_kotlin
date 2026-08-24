def _phase_to_proto_enum_name(phase):
    if phase == "compile":
        return "PLUGIN_PHASE_COMPILE"
    if phase == "stubs":
        return "PLUGIN_PHASE_STUBS"
    fail("Unknown compiler plugin phase: %s" % phase)

def _option_to_json(option):
    # The legacy option encoding packs "key=value" (or a bare key) into the value
    # field; split it into the payload's explicit key/value form. A follow-up
    # commit remodels KtCompilerPluginOption itself as key/value and drops the
    # split.
    key, _, value = option.value.partition("=")
    return {
        "key": key,
        "value": value,
    }

def _plugin_to_json(plugin):
    return {
        "classpath": [entry.path for entry in plugin.classpath.to_list()],
        "id": plugin.id,
        "options": [_option_to_json(option) for option in plugin.options],
        "phases": [_phase_to_proto_enum_name(phase) for phase in plugin.phases],
    }

def _plugins_payload_json(plugins):
    return json.encode({
        "plugins": [_plugin_to_json(plugin) for plugin in plugins],
    })

plugin_payload = struct(
    plugins_payload_json = _plugins_payload_json,
)
