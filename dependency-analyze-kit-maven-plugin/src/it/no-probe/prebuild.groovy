// A probe file left by an earlier build: the goal must skip it (it is not even valid JSON) and say so.
def stale = new File(basedir, 'target/dependency-analyze-kit/context-stale.json')
stale.parentFile.mkdirs()
stale.text = '{ stale'
stale.setLastModified(0L)
return true
