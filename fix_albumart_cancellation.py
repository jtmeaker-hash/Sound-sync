import re
with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'r') as f:
    text = f.read()

text = text.replace('import java.util.Locale\n', 'import java.util.Locale\nimport kotlinx.coroutines.ensureActive\n')
text = text.replace('decodeFileToBitmap(', 'ensureActive()\n        decodeFileToBitmap(')
text = text.replace('decodeStreamToBitmap(', 'ensureActive()\n        decodeStreamToBitmap(')
text = text.replace('decodeByteArrayToBitmap(', 'ensureActive()\n        decodeByteArrayToBitmap(')
text = text.replace('extractEmbeddedPicture(', 'ensureActive()\n        extractEmbeddedPicture(')

with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'w') as f:
    f.write(text)
print("Added cancellation checks")
