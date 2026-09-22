import re

with open('app/src/main/java/com/example/ui/MainDjScreen.kt', 'r') as f:
    content = f.read()

# Fix line 1314
content = re.sub(r'isLoading = isLoading,\n\s+analysisProgressFlow = viewModel\.analysisProgressPercent,', 'isLoading = isLoading,\n        analysisProgressPercent = analysisProgressPercent,', content)

with open('app/src/main/java/com/example/ui/MainDjScreen.kt', 'w') as f:
    f.write(content)

