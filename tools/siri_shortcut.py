#!/usr/bin/env python3
"""Builds the "Projector" Siri shortcut that the remote offers to iPhones (res/raw/siri_shortcut.shortcut).

Dictate Text -> URL-encode -> Get Contents of URL (<address>?k=<key>&say=<words>) -> Speak Text. The address with
the phone's voice key is asked once, when the shortcut is added (an import question), so one signed file serves
every phone. iPhones import only shortcuts signed by Apple, so sign it on a Mac signed into iCloud:

    python3 tools/siri_shortcut.py /tmp/Projector.shortcut
    shortcuts sign --mode anyone --input /tmp/Projector.shortcut \
        --output app/src/main/res/raw/siri_shortcut.shortcut

The signature carries Apple's certificate with a random signer id, not a name or e-mail address.
"""
import plistlib
import sys
import uuid

A, B, E, C = [str(uuid.uuid4()).upper() for _ in range(4)]


def tok(parts):
    s, att = "", {}
    for p in parts:
        if isinstance(p, str):
            s += p
        else:
            att["{%d, 1}" % len(s)] = {"OutputUUID": p[0], "Type": "ActionOutput", "OutputName": p[1]}
            s += "￼"
    return {"Value": {"string": s, "attachmentsByRange": att}, "WFSerializationType": "WFTextTokenString"}


def action(ident, params):
    return {"WFWorkflowActionIdentifier": "is.workflow.actions." + ident, "WFWorkflowActionParameters": params}


workflow = {
    "WFWorkflowActions": [
        action("gettext", {"UUID": A, "WFTextActionText": "http://192.168.1.8:8080/api/do?k=PASTE-YOUR-KEY&say="}),
        action("dictatetext", {"UUID": B}),
        action("urlencode", {"UUID": E, "WFInput": tok([(B, "Dictated Text")])}),
        action("downloadurl", {"UUID": C, "WFHTTPMethod": "GET", "WFURL": tok([(A, "Text"), (E, "URL Encoded Text")])}),
        action("speaktext", {"WFText": tok([(C, "Contents of URL")])}),
    ],
    "WFWorkflowImportQuestions": [{
        "ActionIndex": 0, "Category": "Parameter", "DefaultValue": "", "ParameterKey": "WFTextActionText",
        "Text": "Paste your projector address (the remote copied it for you: tap here, then Paste).",
    }],
    "WFWorkflowClientVersion": "2607.0.2", "WFWorkflowMinimumClientVersion": 900,
    "WFWorkflowMinimumClientVersionString": "900",
    "WFWorkflowIcon": {"WFWorkflowIconStartColor": 4282601983, "WFWorkflowIconGlyphNumber": 59511},
    "WFWorkflowTypes": [], "WFWorkflowInputContentItemClasses": [], "WFWorkflowOutputContentItemClasses": [],
    "WFWorkflowHasShortcutInputVariables": False, "WFQuickActionSurfaces": [],
}

with open(sys.argv[1] if len(sys.argv) > 1 else "Projector.shortcut", "wb") as out:
    plistlib.dump(workflow, out, fmt=plistlib.FMT_BINARY)
