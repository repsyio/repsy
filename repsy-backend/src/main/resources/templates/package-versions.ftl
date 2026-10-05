<!DOCTYPE html>

<html lang="en">
<head>
    <title>Links for ${packageName?html}</title>
</head>

<body>
<h1>Links for ${packageName?html}</h1>
<#list archiveFiles as file>
    <#if file.getRequiresPython()?has_content>
        <a href="${repoUri?html}/${packageName?html}/-/${file.getFilename()?html}#${hashAlgorithm?html}=${file.getFileHash()?html}" data-requires-python="${file.getRequiresPython()}">${file.getFilename()?html}</a><br/>
    <#else>
        <a href="${repoUri?html}/${packageName?html}/-/${file.getFilename()?html}#${hashAlgorithm?html}=${file.getFileHash()?html}">${file.getFilename()?html}</a><br/>
    </#if>
</#list>
</body>
</html>
