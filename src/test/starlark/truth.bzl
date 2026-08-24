"""
Collection of utility functions for the action subject
"""

def fail_messages_in(target_subject):
    return target_subject.failures().transform(
        desc = "failure.message",
        map_each = lambda f: f.partition("Error in fail:")[2].strip() if "Error in fail:" in f else f,
    )

def flags_and_values_of(action_subject):
    return action_subject.argv().transform(desc = "parsed()", loop = _action_subject_parse_flags)

def payload_plugins_of(action_subject):
    """The --plugins_payload plugins, one normalized string per plugin."""
    return action_subject.argv().transform(
        desc = "plugins payload plugins",
        loop = _action_subject_parse_payload_plugins,
    )

def _action_subject_parse_payload_plugins(argv):
    if argv == None:
        return []
    payload = None
    for i, arg in enumerate(argv):
        if arg == "--plugins_payload" and i + 1 < len(argv):
            payload = argv[i + 1]
            break
    if payload == None:
        return []
    return [
        "id={id} classpath=[{classpath}] phases=[{phases}] options=[{options}]".format(
            id = plugin["id"],
            classpath = ",".join([entry.rsplit("/", 1)[-1] for entry in plugin["classpath"]]),
            phases = ",".join(plugin["phases"]),
            options = ",".join(["%s=%s" % (o["key"], o["value"]) for o in plugin["options"]]),
        )
        for plugin in json.decode(payload)["plugins"]
    ]

def _action_subject_parse_flags(argv):
    parsed_flags = {}

    # argv might be none for e.g. builtin actions
    if argv == None:
        return parsed_flags
    last_flag = None
    for arg in argv:
        value = None
        if arg == "--":
            # skip the rest of the arguments, this is standard end of the flags.
            break
        if arg.startswith("-"):
            if "=" in arg:
                last_flag, value = arg.split("=", 1)
            else:
                last_flag = arg
        elif last_flag:
            # have a flag, therefore this is probably an associated argument
            value = arg
        else:
            # skip non-flag arguments
            continue

        # only set the value if it exists
        if value:
            parsed_flags.setdefault(last_flag, []).append(value)
    return parsed_flags.items()
