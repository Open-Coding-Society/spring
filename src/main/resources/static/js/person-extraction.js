import BlobBuilder from "./extraction.js";

async function getPeople(){
    let data1;
    await fetch("/mvc/extract/all/person", {
                method: "GET",
                cache: "no-cache",
        }).then((response) => response.json()).then((data) => {
           data1=data;
    })
    return data1;
}

document.getElementById("export-all").addEventListener("click",async ()=>{
    let content = await getPeople();
    const blob = new BlobBuilder(BlobBuilder.fileTypeEnum.json,content);
    blob.downloadBlob("persons");
})