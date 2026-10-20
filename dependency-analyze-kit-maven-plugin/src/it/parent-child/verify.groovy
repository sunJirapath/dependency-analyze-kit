import groovy.json.JsonSlurper

def report = new JsonSlurper().parse(new File(basedir, 'target/dependency-analyze-kit/report.json'))
assert report.probeReports >= 2 : report
assert report.dependencies.find { it.dependency == 'io.github.sunjirapath.it:feature-starter' }.verdict == 'USED_BEAN' : report
def contexts = new File(basedir, 'target/dependency-analyze-kit').listFiles().findAll { it.name.startsWith('context-') }.collect { new JsonSlurper().parse(it) }
def child = contexts.find { it.parentContextKey != null }
assert child != null
assert contexts.any { it.contextKey == child.parentContextKey && it.id == child.id && it.contextKey != child.contextKey }
return true
