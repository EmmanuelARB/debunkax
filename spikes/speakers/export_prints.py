#!/usr/bin/env python3
"""Exports live.py's TitaNet voice prints and the model into the app's assets (speaker recognition, ADR 0003). Run it before building the app.

    spikes/stt/.venv/bin/python spikes/speakers/export_prints.py
"""
import json
import shutil

import live

ASSETS = live.HERE.parents[1] / "app" / "src" / "main" / "assets" / "speakers"
DISPLAY = {
    "attal": "Gabriel Attal", "bardella": "Jordan Bardella", "bayrou": "François Bayrou", "bompard": "Manuel Bompard",
    "darmanin": "Gérald Darmanin", "faure": "Olivier Faure", "glucksmann": "Raphaël Glucksmann", "lecornu": "Sébastien Lecornu",
    "lepen": "Marine Le Pen", "macron": "Emmanuel Macron", "marechal": "Marion Maréchal", "melenchon": "Jean-Luc Mélenchon",
    "panot": "Mathilde Panot", "philippe": "Édouard Philippe", "retailleau": "Bruno Retailleau", "roussel": "Fabien Roussel",
    "ruffin": "François Ruffin", "tondelier": "Marine Tondelier", "wauquiez": "Laurent Wauquiez", "zemmour": "Éric Zemmour",
}

# Journalists and presenters are enrolled so their voice isn't pinned on a leader; the app skips them
# when it looks for whom a « tu » / « vous » Claim is aimed at. Everyone else is a politician.
JOURNALISTS = {
    "Christophe Barbier", "Gilles Bouleau", "Laurent Delahousse", "Nicolas Demorand", "Sonia Devillers", "Benjamin Duhamel",
    "Ruth Elkrief", "Marc Fauvelle", "Laurence Ferrari", "Anne-Sophie Lapix", "Sonia Mabrouk", "Apolline de Malherbe",
    "Pascal Praud", "David Pujadas", "Darius Rochebin", "Caroline Roux", "Nathalie Saint-Cricq", "Léa Salamé", "Thomas Sotto",
}

# New people (audio/people.json): journalists are enrolled too, so their voice isn't pinned on a leader,
# and are named with their role, since what they say is questions and quotes rather than Claims of their own.
extra = live.AUDIO / "people.json"
for slug, p in (json.loads(extra.read_text()) if extra.exists() else {}).items():
    DISPLAY.setdefault(slug, p["name"])

live.MODEL, live.ex = "titanet", live.extractor("titanet")
names, prints = live.voice_prints()
ASSETS.mkdir(parents=True, exist_ok=True)
shutil.copy(live.HERE / "models" / live.MODELS["titanet"], ASSETS / "titanet_small.onnx")
people = [{"name": DISPLAY.get(s, s), "role": "journalist" if DISPLAY.get(s, s) in JOURNALISTS else "politician",
           "prints": [[round(float(x), 5) for x in p] for n, p in zip(names, prints) if n == s]}
          for s in sorted(set(names))]
(ASSETS / "prints.json").write_text(json.dumps({"model": "titanet_small.onnx", "people": people}, ensure_ascii=False))
print(f"{len(people)} personnes, {len(names)} empreintes -> {ASSETS}")
