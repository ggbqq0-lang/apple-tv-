"""Independently decode Kotlin-created bplists with Python's plistlib and pyatv."""
from pathlib import Path
from pyatv.protocols.companion.keyed_archiver import read_archive_properties
from .core import synthetic_cases

def main():
    for i,(name,text) in enumerate(synthetic_cases().items()):
        source=Path(f"protocol/build/rti-cross-check/case{i}.bplist")
        decoded,uuid=read_archive_properties(source.read_bytes(),
            ["textOperations","keyboardOutput","insertionText"],
            ["textOperations","targetSessionUUID","NS.uuidbytes"])
        assert decoded==text,name
        assert uuid==bytes(range(16)),name
    print("PASS: all 14 Kotlin RTI payloads decoded independently; exact Unicode and session UUID")

if __name__=="__main__":main()
