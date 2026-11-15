// A probe file left by an earlier build: the goal must skip it (it is not even valid JSON) and say so.
def stale = new File(basedir, 'target/dependency-analyze-kit/context-stale.json')
stale.parentFile.mkdirs()
stale.text = '{ stale'
stale.setLastModified(0L)

// Cache extraction can refresh the file timestamp without refreshing the captured evidence.
def touched = new File(stale.parentFile, 'context-touched.json')
touched.text = groovy.json.JsonOutput.toJson([
        fullAutoConfig: true, capturedAt: 1L,
        projectDirectory: basedir.toPath().toRealPath().toString()])
assert touched.setLastModified(System.currentTimeMillis() + 60_000L)
return true
