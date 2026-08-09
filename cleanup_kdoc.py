import os
import re

directory = os.path.dirname(os.path.abspath(__file__))
lines_to_remove = [
    " * Provides high-performance, Zero-GC operations.",
    " * CCW-positive heading standard applied.",
    " * Note: Physical units use standard SI metrics.",
    " * Uses LaTeX math representation for kinematics where applicable."
]

def clean_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original_content = content
    
    # 1. Remove specific lines. We need to handle possible trailing spaces, but exact match is preferred.
    for line in lines_to_remove:
        # regex to remove the line with its trailing newline, handling possible leading whitespace before ` * `
        pattern = r"^[ \t]*" + re.escape(line) + r"\r?\n"
        content = re.sub(pattern, "", content, flags=re.MULTILINE)
        
    # 2. Clean up empty KDoc blocks.
    # A block like:
    # /**
    #  * 
    #  */
    # or just:
    # /**
    #  */
    # Let's write a regex for this.
    # ^[ \t]*/\*\*(?:\r?\n[ \t]*\*[ \t]*)*\r?\n[ \t]*\*/\r?\n
    empty_kdoc_pattern = r"^[ \t]*/\*\*(?:[ \t]*\r?\n(?:[ \t]*\*[ \t]*\r?\n)*)?[ \t]*\*/\r?\n"
    content = re.sub(empty_kdoc_pattern, "", content, flags=re.MULTILINE)
    
    if content != original_content:
        with open(filepath, 'w', encoding='utf-8', newline='') as f:
            f.write(content)

count = 0
for root, dirs, files in os.walk(directory):
    for file in files:
        if file.endswith(".kt"):
            count += 1
            clean_file(os.path.join(root, file))

print(f"Processed {count} files.")
