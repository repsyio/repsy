<!DOCTYPE html>

<html lang="en">
<head>
    <title>Links for ${packageName}</title>
</head>

<body>
<h1>Links for ${packageName}</h1>
<#list archiveFiles as file>
    <#if file.getRequiresPython()?has_content>
        <a href="${repoUri}/${packageName}/-/${file.getFilename()}#${hashAlgorithm}=${file.getFileHash()}" data-requires-python="${file.getRequiresPython()}">${file.getFilename()}</a><br/>
    <#else>
        <a href="${repoUri}/${packageName}/-/${file.getFilename()}#${hashAlgorithm}=${file.getFileHash()}">${file.getFilename()}</a><br/>
    </#if>
</#list>
</body>
</html>
