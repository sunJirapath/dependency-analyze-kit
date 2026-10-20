import groovy.json.JsonSlurper

def report = new JsonSlurper().parse(new File(basedir, 'target/dependency-analyze-kit/report.json'))
assert report.probeReports > 0
assert report.dependencies.find { it.dependency == 'io.github.sunjirapath.it:feature-starter' }.verdict == 'USED_BEAN' : report
def contexts = new File(basedir, 'target/dependency-analyze-kit').listFiles().findAll { it.name.startsWith('context-') }.collect { new JsonSlurper().parse(it) }
assert contexts.any { context ->
    def names = context.beans.collect { it.name }
    names.indexOf('client') < names.indexOf('factoryConfig') && names.indexOf('factoryConfig') < names.indexOf('projectConfig')
}
return true
