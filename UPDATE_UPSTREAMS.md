# Add original repo as upstream (only once)
git remote add upstream https://github.com/quarkiverse/quarkus-langchain4j.git

# Fetch latest changes
git fetch upstream

# Update your main branch
git checkout main
git merge upstream/main